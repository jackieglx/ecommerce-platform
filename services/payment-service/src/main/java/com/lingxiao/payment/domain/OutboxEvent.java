package com.lingxiao.payment.domain;

import java.time.Instant;

/**
 * Outbox event for reliable event publishing.
 * Events are written to outbox table in the same transaction as the aggregate,
 * then relayed to Kafka by a separate process.
 */
public record OutboxEvent(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String topic,
        String kafkaKey,
        String payloadJson,
        OutboxStatus status,
        Instant createdAt,
        Instant lastAttemptAt,
        int attemptCount,
        String lastError,
        String lockedBy,
        Instant leaseUntil
) {
    public enum OutboxStatus {
        PENDING,
        PROCESSING,
        SENT,
        FAILED
    }
}

