package com.lingxiao.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.InventoryReleaseRequestedEvent;
import com.lingxiao.contracts.events.OrderPaidEvent;
import com.lingxiao.contracts.events.OrderTimeoutScheduledEvent;
import com.lingxiao.order.infrastructure.db.spanner.OrderRepository;
import com.lingxiao.order.infrastructure.db.spanner.model.OrderOutboxRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class OrderOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderOutboxPublisher.class);

    private final OrderRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final long sendTimeoutMs;
    private final int maxErrorLength;
    private final long staleMs;
    private final int reclaimBatchSize;
    private final String publisherId;

    public OrderOutboxPublisher(OrderRepository repository,
                                KafkaTemplate<String, Object> kafkaTemplate,
                                ObjectMapper objectMapper,
                                @Value("${order.outbox.batch-size:50}") int batchSize,
                                @Value("${order.outbox.max-attempts:10}") int maxAttempts,
                                @Value("${order.outbox.base-backoff-ms:1000}") long baseBackoffMs,
                                @Value("${order.outbox.send-timeout-ms:5000}") long sendTimeoutMs,
                                @Value("${order.outbox.max-error-length:1000}") int maxErrorLength,
                                @Value("${order.outbox.stale-ms:60000}") long staleMs,
                                @Value("${order.outbox.reclaim-batch-size:100}") int reclaimBatchSize) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
        this.sendTimeoutMs = sendTimeoutMs;
        this.maxErrorLength = maxErrorLength;
        this.staleMs = staleMs;
        this.reclaimBatchSize = reclaimBatchSize;
        // Generate unique publisher ID for this instance
        this.publisherId = "publisher-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("OrderOutboxPublisher initialized with publisherId={}", publisherId);
    }

    @Scheduled(fixedDelayString = "${order.outbox.poll-interval-ms:1000}")
    public void publishPending() {
        Instant now = Instant.now();
        // Claim a batch of records (with locking to prevent multi-instance duplicates)
        List<OrderOutboxRecord> records = repository.claimOutboxBatch(publisherId, now, batchSize);
        
        if (records.isEmpty()) {
            return;
        }

        log.debug("Claimed {} outbox records for publishing", records.size());

        for (OrderOutboxRecord record : records) {
            try {
                dispatch(record);
                // Success: mark as SENT (with owner check)
                boolean updated = repository.markSent(record.outboxId(), publisherId);
                if (updated) {
                    log.debug("Successfully published outbox id={} eventType={} aggregateId={}",
                            record.outboxId(), record.eventType(), record.aggregateId());
                } else {
                    log.warn("markSent returned false (record may have been reclaimed) outboxId={} publisherId={}",
                            record.outboxId(), publisherId);
                }
            } catch (Exception e) {
                // Failure: mark for retry (SENDING -> NEW with backoff)
                // Use current time for each failure to ensure accurate backoff calculation
                Instant failureTime = Instant.now();
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                boolean updated = repository.markRetry(record.outboxId(), publisherId, errorMsg, failureTime, maxAttempts, baseBackoffMs, maxErrorLength);
                if (updated) {
                    log.warn("Failed to publish outbox, will retry id={} eventType={} aggregateId={} attempts={} error={}",
                            record.outboxId(), record.eventType(), record.aggregateId(),
                            record.attempts() + 1, errorMsg, e);
                } else {
                    log.warn("markRetry returned false (record may have been reclaimed) outboxId={} publisherId={} error={}",
                            record.outboxId(), publisherId, errorMsg);
                }
            }
        }
    }

    private void dispatch(OrderOutboxRecord record) throws Exception {
        try {
            switch (record.eventType()) {
                case "ORDER_TIMEOUT_SCHEDULED" -> {
                    OrderTimeoutScheduledEvent event = objectMapper.readValue(
                            record.payloadJson(), OrderTimeoutScheduledEvent.class);
                    kafkaTemplate.send(Topics.ORDER_TIMEOUT_SCHEDULED, record.aggregateId(), event)
                            .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                }
                case "INVENTORY_RELEASE_REQUESTED" -> {
                    InventoryReleaseRequestedEvent event = objectMapper.readValue(
                            record.payloadJson(), InventoryReleaseRequestedEvent.class);
                    kafkaTemplate.send(Topics.INVENTORY_RELEASE_REQUESTED, record.aggregateId(), event)
                            .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                }
                case "ORDER_PAID" -> {
                    OrderPaidEvent event = objectMapper.readValue(
                            record.payloadJson(), OrderPaidEvent.class);
                    kafkaTemplate.send(Topics.ORDER_PAID, record.aggregateId(), event)
                            .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                }
                default -> throw new IllegalArgumentException("Unsupported outbox eventType=" + record.eventType());
            }
        } catch (TimeoutException e) {
            throw new RuntimeException("Kafka send timeout after " + sendTimeoutMs + "ms", e);
        }
    }

    /**
     * Reclaim stale SENDING records that have been locked for too long.
     * This handles cases where a publisher crashed after claiming records.
     */
    @Scheduled(fixedDelayString = "${order.outbox.reclaim-interval-ms:30000}")
    public void reclaimStale() {
        Instant now = Instant.now();
        int reclaimed = repository.reclaimStaleOutbox(now, staleMs, reclaimBatchSize);
        if (reclaimed > 0) {
            log.info("Reclaimed {} stale SENDING outbox records", reclaimed);
        }
    }
}

