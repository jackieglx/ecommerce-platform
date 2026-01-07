package com.lingxiao.payment.infrastructure.db.spanner;

import com.google.cloud.Timestamp;
import com.google.cloud.spanner.*;
import com.lingxiao.common.db.errors.SpannerErrorTranslator;
import com.lingxiao.common.db.repo.BaseRepositorySupport;
import com.lingxiao.common.db.tx.TxRunner;
import com.lingxiao.payment.domain.OutboxEvent;
import com.lingxiao.payment.infrastructure.db.OutboxRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class SpannerOutboxRepository extends BaseRepositorySupport implements OutboxRepository {

    public SpannerOutboxRepository(DatabaseClient databaseClient,
                                   SpannerErrorTranslator translator,
                                   TxRunner txRunner) {
        super(databaseClient, translator, txRunner);
    }

    @Override
    public void insertPending(String id, String aggregateType, String aggregateId, String eventType,
                              String topic, String kafkaKey, String payloadJson) {
        throw new UnsupportedOperationException("Use insertPendingInTx(TransactionContext, ...) instead");
    }

    public void insertPendingInTx(TransactionContext tx, String id, String aggregateType, String aggregateId,
                                  String eventType, String topic, String kafkaKey, String payloadJson) {
        Mutation mutation = Mutation.newInsertBuilder("PaymentOutbox")
                .set("OutboxId").to(id)
                .set("AggregateType").to(aggregateType)
                .set("AggregateId").to(aggregateId)
                .set("EventType").to(eventType)
                .set("Topic").to(topic)
                .set("KafkaKey").to(kafkaKey)
                .set("PayloadJson").to(payloadJson)
                .set("Status").to(OutboxEvent.OutboxStatus.PENDING.name())
                .set("AttemptCount").to(0L)
                .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                .set("LastAttemptAt").to((Timestamp) null)
                .set("LastError").to((String) null)
                .set("LockedBy").to((String) null)
                .set("LeaseUntil").to((Timestamp) null)
                .build();
        tx.buffer(mutation);
    }

    @Override
    public List<OutboxEvent> claimPendingBatch(int limit, String processorId, long leaseDurationSeconds) {
        try {
            return inReadWrite(tx -> {
                Timestamp nowTs = Timestamp.now();
                Instant now = toInstant(nowTs);

                Instant leaseUntil = now.plusSeconds(leaseDurationSeconds);
                Timestamp leaseUntilTs = Timestamp.ofTimeSecondsAndNanos(
                        leaseUntil.getEpochSecond(), leaseUntil.getNano());

                // ✅ 关键：同时支持 reclaim 过期的 PROCESSING
                Statement selectStmt = Statement.newBuilder(
                                "SELECT OutboxId, AggregateType, AggregateId, EventType, Topic, KafkaKey, " +
                                        "PayloadJson, Status, AttemptCount, CreatedAt, LastAttemptAt, LastError, LockedBy, LeaseUntil " +
                                        "FROM PaymentOutbox " +
                                        "WHERE (Status = @pending) " +
                                        "   OR (Status = @processing AND (LeaseUntil IS NULL OR LeaseUntil < @now)) " +
                                        "ORDER BY CreatedAt ASC " +
                                        "LIMIT @limit")
                        .bind("pending").to(OutboxEvent.OutboxStatus.PENDING.name())
                        .bind("processing").to(OutboxEvent.OutboxStatus.PROCESSING.name())
                        .bind("now").to(nowTs)
                        .bind("limit").to((long) limit)
                        .build();

                List<OutboxEvent> events = new ArrayList<>();
                List<Mutation> claimMutations = new ArrayList<>();

                try (ResultSet rs = tx.executeQuery(selectStmt)) {
                    while (rs.next()) {
                        String outboxId = rs.getString("OutboxId");
                        long currentAttemptCount = rs.getLong("AttemptCount");

                        // 直接用 Mutation 写回 PROCESSING + lease（并发抢同一行会在提交时写写冲突，自动 abort 一个事务）
                        Mutation claimMutation = Mutation.newUpdateBuilder("PaymentOutbox")
                                .set("OutboxId").to(outboxId)
                                .set("Status").to(OutboxEvent.OutboxStatus.PROCESSING.name())
                                .set("AttemptCount").to(currentAttemptCount + 1)
                                .set("LastAttemptAt").to(nowTs)
                                .set("LockedBy").to(processorId)
                                .set("LeaseUntil").to(leaseUntilTs)
                                .build();
                        claimMutations.add(claimMutation);

                        events.add(new OutboxEvent(
                                outboxId,
                                rs.getString("AggregateType"),
                                rs.getString("AggregateId"),
                                rs.getString("EventType"),
                                rs.getString("Topic"),
                                rs.getString("KafkaKey"),
                                rs.getString("PayloadJson"),
                                OutboxEvent.OutboxStatus.PROCESSING,
                                toInstant(rs.getTimestamp("CreatedAt")),
                                now,
                                Math.toIntExact(currentAttemptCount + 1),
                                rs.isNull("LastError") ? null : rs.getString("LastError"),
                                processorId,
                                leaseUntil
                        ));
                    }
                }

                if (!claimMutations.isEmpty()) {
                    tx.buffer(claimMutations);
                }
                return events;
            });
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }

    @Override
    public void markSent(String id, String processorId) {
        try {
            inReadWrite(tx -> {
                Timestamp now = Timestamp.now();
                // ✅ 条件更新：只允许持有 lease 的实例落账
                Statement stmt = Statement.newBuilder(
                                "UPDATE PaymentOutbox " +
                                        "SET Status=@sent, LastAttemptAt=@now, LockedBy=NULL, LeaseUntil=NULL " +
                                        "WHERE OutboxId=@id AND Status=@processing AND LockedBy=@lockedBy")
                        .bind("sent").to(OutboxEvent.OutboxStatus.SENT.name())
                        .bind("processing").to(OutboxEvent.OutboxStatus.PROCESSING.name())
                        .bind("now").to(now)
                        .bind("id").to(id)
                        .bind("lockedBy").to(processorId)
                        .build();
                tx.executeUpdate(stmt);
                return null;
            });
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }

    @Override
    public void markFailed(String id, String processorId, int attemptCount, String lastError, boolean permanentFailure) {
        try {
            inReadWrite(tx -> {
                Timestamp now = Timestamp.now();
                OutboxEvent.OutboxStatus newStatus = permanentFailure
                        ? OutboxEvent.OutboxStatus.FAILED
                        : OutboxEvent.OutboxStatus.PENDING;

                Statement stmt = Statement.newBuilder(
                                "UPDATE PaymentOutbox " +
                                        "SET Status=@status, AttemptCount=@attemptCount, LastAttemptAt=@now, LastError=@lastError, " +
                                        "    LockedBy=NULL, LeaseUntil=NULL " +
                                        "WHERE OutboxId=@id AND Status=@processing AND LockedBy=@lockedBy")
                        .bind("status").to(newStatus.name())
                        .bind("attemptCount").to((long) attemptCount)
                        .bind("now").to(now)
                        .bind("lastError").to(lastError != null ? lastError.substring(0, Math.min(lastError.length(), 1000)) : null)
                        .bind("processing").to(OutboxEvent.OutboxStatus.PROCESSING.name())
                        .bind("id").to(id)
                        .bind("lockedBy").to(processorId)
                        .build();

                tx.executeUpdate(stmt);
                return null;
            });
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }

    private Instant toInstant(Timestamp ts) {
        if (ts == null) return null;
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }
}
