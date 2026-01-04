package com.lingxiao.order.application;

import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.OrderCancelledEvent;
import com.lingxiao.contracts.events.OrderTimeoutScheduledEvent;
import com.lingxiao.order.infrastructure.db.spanner.OrderRepository;
import com.lingxiao.order.infrastructure.db.spanner.model.CancelOutcome;
import com.lingxiao.order.infrastructure.db.spanner.model.CancelResult;
import com.lingxiao.order.infrastructure.redis.OrderTimeoutQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class OrderTimeoutProcessor {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutProcessor.class);

    private final OrderTimeoutQueue queue;
    private final OrderRepository repository;
    private final KafkaTemplate<String, OrderCancelledEvent> kafkaTemplate;

    private final int claimBatchSize;
    private final long staleMs;
    private final int reclaimBatchSize;
    private final long maxAttempts;

    public OrderTimeoutProcessor(
            OrderTimeoutQueue queue,
            OrderRepository repository,
            KafkaTemplate<String, OrderCancelledEvent> kafkaTemplate,
            @Value("${order.timeout.claim-batch-size:50}") int claimBatchSize,
            @Value("${order.timeout.stale-ms:30000}") long staleMs,
            @Value("${order.timeout.reclaim-batch-size:100}") int reclaimBatchSize,
            @Value("${order.timeout.max-attempts:10}") long maxAttempts
    ) {
        this.queue = queue;
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.claimBatchSize = claimBatchSize;
        this.staleMs = staleMs;
        this.reclaimBatchSize = reclaimBatchSize;
        this.maxAttempts = maxAttempts;
    }

    @KafkaListener(topics = Topics.ORDER_TIMEOUT_SCHEDULED, groupId = "order-timeout-processor-v1")
    public void onTimeoutScheduled(OrderTimeoutScheduledEvent event) {
        queue.schedule(event.orderId(), event.expireAt());
        log.debug("Scheduled order timeout orderId={} expireAt={}", event.orderId(), event.expireAt());
    }

    @Scheduled(fixedDelayString = "${order.timeout.claim-interval-ms:1000}")
    public void claimAndProcess() {
        var claim = queue.claimDue(claimBatchSize);
        if (claim.orderIds().isEmpty()) return;

        String token = claim.token();
        Instant now = Instant.now();

        for (String orderId : claim.orderIds()) {
            CancelOutcome out;
            try {
                out = repository.cancelIfPending(orderId, now);
            } catch (Exception e) {
                long attempts = queue.incrementAttempt(orderId);
                log.warn("CancelIfPending threw exception orderId={} attempts={}", orderId, attempts, e);

                if (attempts >= maxAttempts) {
                    log.error("Timeout cancel exceeded max attempts, ack to stop looping orderId={} attempts={}",
                            orderId, attempts);
                    queue.ack(orderId, token); // ack 会清 attempts
                }
                // 不 ack -> 等 reclaim 后重试
                continue;
            }

            CancelResult r = out.result();

            switch (r) {
                case CANCELLED -> {
                    publishCancelled(orderId);
                    queue.ack(orderId, token);
                }
                case ALREADY_FINAL, NOT_FOUND -> {
                    queue.ack(orderId, token);
                }
                case NOT_EXPIRED_YET -> {
                    queue.ack(orderId, token);
                    if (out.expireAt() != null) {
                        queue.schedule(orderId, out.expireAt());
                    } else {
                        log.warn("NOT_EXPIRED_YET but expireAt is null, orderId={}", orderId);
                    }
                }
                case RETRYABLE_FAIL -> {
                    long attempts = queue.incrementAttempt(orderId);
                    log.warn("Retryable fail orderId={} attempts={}", orderId, attempts);

                    if (attempts >= maxAttempts) {
                        log.error("Timeout cancel exceeded max attempts, ack to stop looping orderId={} attempts={}",
                                orderId, attempts);
                        queue.ack(orderId, token); // ack 会清 attempts
                    }
                    // 不 ack -> 等 reclaim 后重试
                }
                default -> {
                    // 兜底：未知结果先按可重试处理
                    long attempts = queue.incrementAttempt(orderId);
                    log.warn("Unknown CancelResult, treat as retryable orderId={} result={} attempts={}",
                            orderId, r, attempts);
                    if (attempts >= maxAttempts) {
                        queue.ack(orderId, token);
                    }
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${order.timeout.reclaim-interval-ms:5000}")
    public void reclaimStuck() {
        List<String> reclaimed = queue.reclaim(staleMs, reclaimBatchSize);
        if (!reclaimed.isEmpty()) {
            log.debug("Reclaimed timeouts back to ready count={}", reclaimed.size());
        }
    }

    private void publishCancelled(String orderId) {
        try {
            kafkaTemplate.send(
                    Topics.ORDER_CANCELLED,
                    orderId,
                    new OrderCancelledEvent(orderId, "TIMEOUT", Instant.now())
            );
        } catch (Exception e) {
            log.warn("Failed to publish OrderCancelledEvent orderId={}", orderId, e);
        }
    }
}
