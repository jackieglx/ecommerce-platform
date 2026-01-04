package com.lingxiao.inventory.messaging;

import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.FlashSaleReservedEventV2;
import com.lingxiao.inventory.config.FlashSaleOutboxProperties;
import com.lingxiao.inventory.metrics.FlashSaleMetrics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("local")
public class FlashSaleOutboxPublisher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleOutboxPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, FlashSaleReservedEventV2> kafkaTemplate;
    private final FlashSaleMetrics metrics;

    private final String streamKey;
    private final String group;
    private final String consumerName;

    private final int batchSize;
    private final int maxAttempts;
    private final long retryBaseMs;
    private final long retryCapMs;

    private final long pendingIdleMs;            // stale 标准：idle >= pendingIdleMs -> reclaim/claim stale
    private final long pendingReadIdleMs;        // 少量 pending read 标准：idle >= pendingReadIdleMs -> 允许再读/再尝试
    private final int pendingReadLimit;          // 少量 pending read 每次最多处理多少条

    private final long blockTimeoutMs;
    private final long pendingScanIntervalMs;

    private final String retryAttemptPrefix = "fs:retry:attempt:";
    private final String retryNextPrefix = "fs:retry:next:";

    private final Semaphore inFlight;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fs-outbox-relay");
        t.setDaemon(true);
        return t;
    });

    private volatile long lastPendingScan = 0L;

    public FlashSaleOutboxPublisher(StringRedisTemplate redisTemplate,
                                    KafkaTemplate<String, FlashSaleReservedEventV2> kafkaTemplate,
                                    FlashSaleMetrics metrics,
                                    FlashSaleOutboxProperties outboxProperties,
                                    @Value("${inventory.flashsale.outbox.consumer:fs-pub-1}") String consumerName,
                                    @Value("${inventory.flashsale.outbox.batch-size:50}") int batchSize,
                                    @Value("${inventory.flashsale.outbox.retry.base-ms:500}") long retryBaseMs,
                                    @Value("${inventory.flashsale.outbox.retry.cap-ms:60000}") long retryCapMs,
                                    @Value("${inventory.flashsale.outbox.retry.max-attempts:5}") int maxAttempts,
                                    @Value("${inventory.flashsale.outbox.max-in-flight:100}") int maxInFlight,
                                    @Value("${inventory.flashsale.outbox.pending-idle-ms:30000}") long pendingIdleMs,
                                    // 新增：少量 pending read 的 idle 过滤阈值 + 每次处理数量
                                    @Value("${inventory.flashsale.outbox.pending-read-idle-ms:2000}") long pendingReadIdleMs,
                                    @Value("${inventory.flashsale.outbox.pending-read-limit:20}") int pendingReadLimit) {

        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;

        this.streamKey = outboxProperties.streamKey();
        this.group = outboxProperties.group();
        this.consumerName = consumerName;

        this.batchSize = batchSize;
        this.retryBaseMs = retryBaseMs;
        this.retryCapMs = retryCapMs;
        this.maxAttempts = maxAttempts;

        this.inFlight = new Semaphore(maxInFlight);

        this.pendingIdleMs = pendingIdleMs;
        this.pendingReadIdleMs = pendingReadIdleMs;
        this.pendingReadLimit = Math.max(1, pendingReadLimit);

        this.blockTimeoutMs = outboxProperties.blockTimeoutMs();
        this.pendingScanIntervalMs = outboxProperties.pendingScanIntervalMs();

        ensureGroup();
    }

    private void ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), group);
        } catch (Exception e) {
            // stream 不存在或 BUSYGROUP 等都可能走到这：尝试创建一个 dummy entry 再建 group
            try {
                redisTemplate.opsForStream().add(
                        StreamRecords.mapBacked(Map.of("init", "1")).withStreamKey(streamKey)
                );
                redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), group);
            } catch (Exception ignore) {
                // BUSYGROUP / 已存在等：忽略
            }
        }
    }

    // ---------------------- 主循环 ----------------------

    private void runLoop() {
        while (running.get()) {
            try {
                @SuppressWarnings("unchecked")
                List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream().read(
                        Consumer.from(group, consumerName),
                        StreamReadOptions.empty()
                                .count(batchSize)
                                .block(Duration.ofMillis(blockTimeoutMs)),
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed())
                );

                handleMessages(messages);

                long now = System.currentTimeMillis();
                if (now - lastPendingScan >= pendingScanIntervalMs) {
                    scanPendingAndClaim();
                    lastPendingScan = now;
                }
            } catch (Exception e) {
                log.warn("Outbox relay loop error", e);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * pending 处理策略（你选的那种）：
     * 1) claim stale（跨 consumer 恢复宕机的 consumer 持有的 pending）
     * 2) 少量 pending read：只拿“idle 足够久”的少量 pending 再尝试（避免扫全 pending 空转）
     */
    private void scanPendingAndClaim() {
        claimStalePending();          // 恢复“卡住的/宕机的”
        claimRetryEligiblePending();  // 少量 + idle 过滤的 pending 再尝试
    }

    // ---------------------- Pending: stale 恢复 ----------------------

    /**
     * 跨 consumer：把 idle >= pendingIdleMs 的 pending 任务 claim 到当前 consumer，然后处理。
     */
    private void claimStalePending() {
        try {
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(streamKey, group, Range.unbounded(), batchSize);

            if (pending == null || pending.isEmpty()) {
                return;
            }

            List<RecordId> ids = pending.stream()
                    .filter(pm -> pm.getElapsedTimeSinceLastDelivery() != null
                            && pm.getElapsedTimeSinceLastDelivery().toMillis() >= pendingIdleMs)
                    .limit(batchSize)
                    .map(PendingMessage::getIdAsString)
                    .map(RecordId::of)
                    .toList();

            if (ids.isEmpty()) {
                return;
            }

            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                    .claim(streamKey, group, consumerName, Duration.ofMillis(pendingIdleMs),
                            ids.toArray(new RecordId[0]));

            handleMessages(claimed);
        } catch (Exception e) {
            log.warn("Pending claim(stale) failed stream={} group={} consumer={}", streamKey, group, consumerName, e);
        }
    }

    // ---------------------- Pending: 少量 + idle 过滤再尝试 ----------------------

    /**
     * 仅针对“当前 consumer 自己的 pending”：挑 idle >= pendingReadIdleMs 的少量记录，再 claim 后处理。
     * 目的：避免 XREADGROUP 0-0 每次扫全 pending 导致 CPU 空转。
     */
    private void claimRetryEligiblePending() {
        try {
            // 先看当前 consumer 的 pending（不跨 consumer）
            int scanCount = Math.max(pendingReadLimit * 5, pendingReadLimit);
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(streamKey, Consumer.from(group, consumerName), Range.unbounded(), scanCount);

            if (pending == null || pending.isEmpty()) {
                return;
            }

            List<RecordId> ids = pending.stream()
                    .filter(pm -> pm.getElapsedTimeSinceLastDelivery() != null
                            && pm.getElapsedTimeSinceLastDelivery().toMillis() >= pendingReadIdleMs)
                    .limit(pendingReadLimit)
                    .map(PendingMessage::getIdAsString)
                    .map(RecordId::of)
                    .toList();

            if (ids.isEmpty()) {
                return;
            }

            // claim 会把 idle 重置为 0，能天然避免“下一次 scan 又立刻把同一条拿出来”的空转
            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                    .claim(streamKey, group, consumerName, Duration.ofMillis(pendingReadIdleMs),
                            ids.toArray(new RecordId[0]));

            handleMessages(claimed);
        } catch (Exception e) {
            log.warn("Pending claim(retry-eligible) failed stream={} group={} consumer={}", streamKey, group, consumerName, e);
        }
    }

    // ---------------------- 处理消息（发 Kafka + ack/delete） ----------------------

    private void handleMessages(List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> value = record.getValue();
            String eventId = toStr(value.get("eventId"));

            // 无 eventId：当作 poison 直接 ack 清掉，避免阻塞 PEL
            if (eventId == null || eventId.isBlank()) {
                ackAndDelete(record);
                continue;
            }

            long now = System.currentTimeMillis();

            // 未到重试时间：不 ack，留在 pending
            if (!readyToProcess(eventId, now)) {
                continue;
            }

            // 若已经超过 maxAttempts：直接走 DLQ（不要再发主 topic）
            int currentAttempt = getAttempt(eventId);
            if (currentAttempt > maxAttempts) {
                sendDlqAsync(record, eventId);
                continue;
            }

            FlashSaleReservedEventV2 event;
            try {
                event = toEvent(value);
            } catch (Exception parseEx) {
                log.warn("Invalid outbox payload, ack it as poison recordId={}", record.getId(), parseEx);
                ackAndDelete(record);
                continue;
            }

            inFlight.acquireUninterruptibly();
            try {
                kafkaTemplate.send(new ProducerRecord<>(Topics.FLASH_SALE_RESERVED_V2, event.skuId(), event))
                        .whenComplete((res, ex) -> {
                            try {
                                if (ex != null) {
                                    onSendFailed(record, eventId, ex);
                                } else {
                                    onSendSuccess(record, eventId);
                                }
                            } finally {
                                inFlight.release();
                            }
                        });
            } catch (Exception syncEx) {
                try {
                    onSendFailed(record, eventId, syncEx);
                } finally {
                    inFlight.release();
                }
            }
        }
    }

    private void onSendSuccess(MapRecord<String, Object, Object> record, String eventId) {
        try {
            clearRetry(eventId);
            ackAndDelete(record);
            metrics.incPublisherSuccess();
        } catch (Exception e) {
            log.warn("Success path ack/delete failed recordId={}", record.getId(), e);
        }
    }

    private void onSendFailed(MapRecord<String, Object, Object> record, String eventId, Throwable ex) {
        metrics.incPublisherFail();

        int attempt = incrementAttempt(eventId);
        long now = System.currentTimeMillis();

        log.warn("Outbox publish failed eventId={} attempt={}", eventId, attempt, ex);

        if (attempt > maxAttempts) {
            // 进入 DLQ 流程：下次也只尝试 DLQ
            setNext(eventId, now + retryCapMs);
            sendDlqAsync(record, eventId);
            return;
        }

        metrics.incPublisherRetry();
        long backoff = computeBackoffMs(attempt);
        setNext(eventId, now + backoff);
    }

    private long computeBackoffMs(int attempt) {
        // attempt 从 1 开始：500, 1000, 2000... capped
        long factor = 1L << Math.min(attempt, 10);
        long backoff = retryBaseMs * factor;
        return Math.min(backoff, retryCapMs);
    }

    // ---------------------- DLQ（需要受 inFlight 限制） ----------------------

    /**
     * 当已超过 maxAttempts 时，从主线程路径直接走 DLQ（也要占用 permit，避免 DLQ 打爆 Kafka）。
     */
    private void sendDlqAsync(MapRecord<String, Object, Object> record, String eventId) {
        FlashSaleReservedEventV2 event;
        try {
            event = toEvent(record.getValue());
        } catch (Exception e) {
            // DLQ 都构造不出来：当 poison，直接 ack 清掉
            log.error("Failed to build DLQ event, ack as poison recordId={}", record.getId(), e);
            ackAndDelete(record);
            clearRetry(eventId);
            return;
        }

        // 这里不额外 acquire permit：调用方在失败回调时已持有 permit
        kafkaTemplate.send(new ProducerRecord<>(Topics.FLASH_SALE_RESERVED_DLQ_V2, event.skuId(), event))
                .whenComplete((res, ex) -> {
                    try {
                        if (ex != null) {
                            log.error("Failed to send DLQ for recordId={}", record.getId(), ex);
                            setNext(eventId, System.currentTimeMillis() + retryCapMs);
                            // 不 ack：留在 pending 等下次再发 DLQ
                        } else {
                            clearRetry(eventId);
                            ackAndDelete(record);
                        }
                    } finally {
                        inFlight.release();
                    }
                });
    }

    // ---------------------- Retry state ----------------------

    private boolean readyToProcess(String eventId, long now) {
        String next = redisTemplate.opsForValue().get(retryNextPrefix + eventId);
        if (next == null) {
            return true;
        }
        try {
            return now >= Long.parseLong(next);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private int getAttempt(String eventId) {
        String s = redisTemplate.opsForValue().get(retryAttemptPrefix + eventId);
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int incrementAttempt(String eventId) {
        Long v = redisTemplate.opsForValue().increment(retryAttemptPrefix + eventId);
        int val = v == null ? 1 : v.intValue();
        redisTemplate.expire(retryAttemptPrefix + eventId, Duration.ofHours(24));
        return val;
    }

    private void setNext(String eventId, long nextTs) {
        redisTemplate.opsForValue().set(retryNextPrefix + eventId, Long.toString(nextTs), Duration.ofHours(24));
    }

    private void clearRetry(String eventId) {
        redisTemplate.delete(retryAttemptPrefix + eventId);
        redisTemplate.delete(retryNextPrefix + eventId);
    }

    // ---------------------- Ack/Delete ----------------------

    private void ackAndDelete(MapRecord<String, Object, Object> record) {
        try {
            Long acked = redisTemplate.opsForStream().acknowledge(streamKey, group, record.getId());
            if (acked != null && acked > 0) {
                redisTemplate.opsForStream().delete(streamKey, record.getId());
            } else {
                // ack 失败就别 delete，避免 PEL 残留 + 数据丢失
                log.warn("Ack returned 0 for recordId={}", record.getId());
            }
        } catch (Exception e) {
            log.warn("Ack/Delete failed for recordId={}", record.getId(), e);
        }
    }

    // ---------------------- Map -> Event ----------------------

    private FlashSaleReservedEventV2 toEvent(Map<Object, Object> map) {
        return new FlashSaleReservedEventV2(
                toStr(map.get("eventId")),
                toStr(map.get("orderId")),
                toStr(map.get("userId")),
                toStr(map.get("skuId")),
                Long.parseLong(toStrOrDefault(map.get("qty"), "1")),
                Long.parseLong(toStrOrDefault(map.get("priceCents"), "0")),
                toStr(map.get("currency")),
                Instant.parse(toStr(map.get("occurredAt"))),
                Instant.parse(toStr(map.get("expireAt")))
        );
    }

    private String toStr(Object v) {
        return v == null ? null : v.toString();
    }

    private String toStrOrDefault(Object v, String def) {
        return v == null ? def : v.toString();
    }

    // ---------------------- SmartLifecycle ----------------------

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::runLoop);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
