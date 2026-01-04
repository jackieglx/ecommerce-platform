package com.lingxiao.order.infrastructure.db.spanner.model;

import java.time.Instant;

public record OrderOutboxRecord(
        String outboxId,
        String eventType,
        String aggregateId,
        String payloadJson,
        OutboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        String lockedBy,
        Instant lockedAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
}

