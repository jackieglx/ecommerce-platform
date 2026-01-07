package com.lingxiao.payment.infrastructure.db;

import com.lingxiao.payment.domain.OutboxEvent;

import java.util.List;

public interface OutboxRepository {
    void insertPending(String id, String aggregateType, String aggregateId, String eventType,
                       String topic, String kafkaKey, String payloadJson);

    List<OutboxEvent> claimPendingBatch(int limit, String processorId, long leaseDurationSeconds);

    void markSent(String id, String processorId);

    void markFailed(String id, String processorId, int attemptCount, String lastError, boolean permanentFailure);
}


