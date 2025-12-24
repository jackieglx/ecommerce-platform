package com.lingxiao.inventory.messaging;

import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.FlashSaleReservedEvent;
import com.lingxiao.inventory.config.FlashSaleOutboxProperties;
import com.lingxiao.inventory.metrics.FlashSaleMetrics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Component
@Profile("local")
public class FlashSaleOutboxPublisher implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleOutboxPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, FlashSaleReservedEvent> kafkaTemplate;

    private final String streamKey;
    private final String group;
    private final String consumerName;
    private final int batchSize;
    private final long retryBaseMs;
    private final long retryCapMs;
    private final int maxAttempts;
    private final String retryAttemptPrefix;
    private final String retryNextPrefix;
    private final Semaphore inFlight;
    private final FlashSaleMetrics metrics;

    public FlashSaleOutboxPublisher(StringRedisTemplate redisTemplate,
                                    KafkaTemplate<String, FlashSaleReservedEvent> kafkaTemplate,
                                    FlashSaleMetrics metrics,
                                    FlashSaleOutboxProperties outboxProperties,
                                    @org.springframework.beans.factory.annotation.Value("${inventory.flashsale.outbox.consumer:fs-pub-1}") String consumerName,
                                    @org.springframework.beans.factory.annotation.Value("${inventory.flashsale.outbox.batch-size:50}") int batchSize,
                                    @org.springframework.beans.factory.annotation.Value("${inventory.flashsale.outbox.retry.base-ms:500}") long retryBaseMs,
                                    @org.springframework.beans.factory.annotation.Value("${inventory.flashsale.outbox.retry.cap-ms:60000}") long retryCapMs,
                                    @org.springframework.beans.factory.annotation.Value("${inventory.flashsale.outbox.retry.max-attempts:5}") int maxAttempts,
                                    @org.springframework.beans.factory.annotation.Value("${inventory.flashsale.outbox.max-in-flight:100}") int maxInFlight) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.streamKey = outboxProperties.streamKey();
        this.group = outboxProperties.group();
        this.consumerName = consumerName;
        this.batchSize = batchSize;
        this.retryBaseMs = retryBaseMs;
        this.retryCapMs = retryCapMs;
        this.maxAttempts = maxAttempts;
        this.retryAttemptPrefix = "fs:retry:attempt:";
        this.retryNextPrefix = "fs:retry:next:";
        this.inFlight = new Semaphore(maxInFlight);
        this.metrics = metrics;
        ensureGroup();
    }

    private void ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), group);
        } catch (Exception e) {
            // BUSYGROUP is fine
        }
    }

    @Scheduled(fixedDelayString = "2000")
    @SuppressWarnings("unchecked")
    public void poll() {
        // read new messages
        List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream().read(
                Consumer.from(group, consumerName),
                StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(1000)),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );
        handleMessages(messages);

        // process pending
        List<MapRecord<String, Object, Object>> pending = redisTemplate.opsForStream().read(
                Consumer.from(group, consumerName),
                StreamReadOptions.empty().count(batchSize),
                StreamOffset.create(streamKey, ReadOffset.from("0-0"))
        );
        handleMessages(pending);
    }

    private void handleMessages(List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            String eventId = toStr(record.getValue().get("eventId"));
            if (eventId == null) {
                ack(record);
                continue;
            }
            if (!readyToProcess(eventId, System.currentTimeMillis())) {
                continue;
            }
            FlashSaleReservedEvent event = toEvent(record.getValue());
            inFlight.acquireUninterruptibly();
                kafkaTemplate.send(new ProducerRecord<>(Topics.FLASH_SALE_RESERVED, event.skuId(), event))
                    .whenComplete((res, ex) -> {
                        if (ex != null) {
                            handleFailure(record, eventId, ex);
                        } else {
                            try {
                                clearRetry(eventId);
                                ack(record);
                                metrics.incPublisherSuccess();
                            } finally {
                                inFlight.release();
                            }
                        }
                    });
        }
    }

    private void handleFailure(MapRecord<String, Object, Object> record, String eventId, Throwable ex) {
        metrics.incPublisherFail();
        try {
            int attempt = incrementAttempt(eventId);
            metrics.incPublisherRetry();
            long backoff = Math.min(retryBaseMs * (1L << Math.min(attempt, 10)), retryCapMs);
            setNext(eventId, System.currentTimeMillis() + backoff);
            log.warn("Outbox publish failed eventId={} attempt={} backoffMs={}", eventId, attempt, backoff, ex);
            if (attempt > maxAttempts) {
                boolean dlqOk = sendDlq(record);
                if (dlqOk) {
                    clearRetry(eventId);
                    ack(record);
                } else {
                    // keep in stream to retry DLQ next poll
                    setNext(eventId, System.currentTimeMillis() + retryCapMs);
                }
            }
        } finally {
            inFlight.release();
        }
    }

    private boolean readyToProcess(String eventId, long now) {
        String next = redisTemplate.opsForValue().get(retryNextPrefix + eventId);
        if (next == null) return true;
        try {
            long ts = Long.parseLong(next);
            return now >= ts;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private int incrementAttempt(String eventId) {
        return redisTemplate.opsForValue()
                .increment(retryAttemptPrefix + eventId)
                .intValue();
    }

    private void setNext(String eventId, long nextTs) {
        redisTemplate.opsForValue().set(retryNextPrefix + eventId, Long.toString(nextTs));
    }

    private void clearRetry(String eventId) {
        redisTemplate.delete(retryAttemptPrefix + eventId);
        redisTemplate.delete(retryNextPrefix + eventId);
    }

    private boolean sendDlq(MapRecord<String, Object, Object> record) {
        try {
            FlashSaleReservedEvent event = toEvent(record.getValue());
            kafkaTemplate.send(new ProducerRecord<>(Topics.FLASH_SALE_RESERVED_DLQ, event.skuId(), event)).get();
            return true;
        } catch (Exception e) {
            log.error("Failed to send DLQ for record {}", record.getId(), e);
            return false;
        }
    }

    private void ack(MapRecord<String, Object, Object> record) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, group, record.getId());
            redisTemplate.opsForStream().delete(streamKey, record.getId());
        } catch (Exception e) {
            log.warn("Ack failed for {}", record.getId(), e);
        }
    }

    private FlashSaleReservedEvent toEvent(Map<Object, Object> map) {
        return new FlashSaleReservedEvent(
                toStr(map.get("eventId")),
                toStr(map.get("orderId")),
                toStr(map.get("skuId")),
                Long.parseLong(toStrOrDefault(map.get("qty"), "1")),
                Instant.parse(toStr(map.get("occurredAt")))
        );
    }

    private String toStr(Object v) {
        return v == null ? null : v.toString();
    }

    private String toStrOrDefault(Object v, String def) {
        return v == null ? def : v.toString();
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        // unused, polling via @Scheduled
    }
}

