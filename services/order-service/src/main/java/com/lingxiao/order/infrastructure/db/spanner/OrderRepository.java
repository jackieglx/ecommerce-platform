package com.lingxiao.order.infrastructure.db.spanner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.Value;
import com.lingxiao.common.db.tx.TxRunner;
import com.lingxiao.contracts.events.InventoryReleaseRequestedEvent;
import com.lingxiao.contracts.events.FlashSaleReservedEventV2;
import com.lingxiao.order.infrastructure.db.spanner.model.CancelOutcome;
import com.lingxiao.order.infrastructure.db.spanner.model.CancelResult;
import com.lingxiao.order.infrastructure.db.spanner.model.OrderOutboxRecord;
import com.lingxiao.order.infrastructure.db.spanner.model.OutboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class OrderRepository {

    private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);

    private final DatabaseClient databaseClient;
    private final TxRunner txRunner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OrderRepository(DatabaseClient databaseClient, TxRunner txRunner) {
        this.databaseClient = databaseClient;
        this.txRunner = txRunner;
    }

    public void createFromFlashSaleEvent(FlashSaleReservedEventV2 event) {
        txRunner.runReadWrite(tx -> {
            String idempotencyKey = "fs:v2:" + event.eventId();
            Key key = Key.of(event.userId(), idempotencyKey);
            Struct existing = tx.readRow("OrderIdempotency", key, List.of("OrderId"));
            if (existing != null) {
                log.debug("Duplicate flash sale event ignored eventId={} orderId={} userId={}", event.eventId(), event.orderId(), event.userId());
                return null;
            }

            Instant expireAt = event.expireAt() != null ? event.expireAt() : event.occurredAt().plusSeconds(300);
            long subtotal = event.priceCents() * event.qty();
            long discount = 0;
            long tax = 0;
            long shipping = 0;
            long total = subtotal - discount + tax + shipping;

            Mutation orderMutation = Mutation.newInsertBuilder("Orders")
                    .set("OrderId").to(event.orderId())
                    .set("UserId").to(event.userId())
                    .set("Status").to("PENDING_PAYMENT")
                    .set("StatusVersion").to(1L)
                    .set("ExpireAt").to(Timestamp.ofTimeSecondsAndNanos(expireAt.getEpochSecond(), expireAt.getNano()))
                    .set("Currency").to(event.currency())
                    .set("SubtotalCents").to(subtotal)
                    .set("DiscountCents").to(discount)
                    .set("TaxCents").to(tax)
                    .set("ShippingCents").to(shipping)
                    .set("TotalCents").to(total)
                    .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();

            Mutation itemMutation = Mutation.newInsertBuilder("OrderItems")
                    .set("OrderId").to(event.orderId())
                    .set("LineId").to(1L)
                    .set("SkuId").to(event.skuId())
                    .set("Quantity").to(event.qty())
                    .set("UnitPriceCents").to(event.priceCents())
                    .set("Currency").to(event.currency())
                    .set("LineSubtotalCents").to(subtotal)
                    .set("LineDiscountCents").to(discount)
                    .set("LineTaxCents").to(tax)
                    .set("LineTotalCents").to(total)
                    .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();

            Mutation idemMutation = Mutation.newInsertBuilder("OrderIdempotency")
                    .set("UserId").to(event.userId())
                    .set("IdempotencyKey").to(idempotencyKey)
                    .set("OrderId").to(event.orderId())
                    .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();

            List<Mutation> mutations = new ArrayList<>();
            mutations.add(orderMutation);
            mutations.add(itemMutation);
            mutations.add(idemMutation);
            String timeoutOutboxId = UUID.randomUUID().toString();
            mutations.add(buildOutboxInsert(
                    timeoutOutboxId,
                    "ORDER_TIMEOUT_SCHEDULED",
                    event.orderId(),
                    toJson(new com.lingxiao.contracts.events.OrderTimeoutScheduledEvent(event.orderId(), expireAt)),
                    expireAt.minusSeconds(1) // Set NextAttemptAt slightly before expireAt for immediate processing
            ));
            tx.buffer(mutations);
            return null;
        });
    }

    public CancelOutcome cancelIfPending(String orderId, Instant now) {
        return txRunner.runReadWrite(tx -> {
            Struct row = tx.readRow("Orders", Key.of(orderId),
                    List.of("Status", "StatusVersion", "ExpireAt"));
            if (row == null) {
                return new CancelOutcome(CancelResult.NOT_FOUND, null);
            }
            String status = row.getString("Status");
            if (!"PENDING_PAYMENT".equals(status)) {
                return new CancelOutcome(CancelResult.ALREADY_FINAL, null);
            }
            Instant expireAt = Instant.ofEpochSecond(row.getTimestamp("ExpireAt").getSeconds(),
                    row.getTimestamp("ExpireAt").getNanos());
            if (expireAt.isAfter(now)) {
                return new CancelOutcome(CancelResult.NOT_EXPIRED_YET, expireAt);
            }
            long version = row.getLong("StatusVersion");

            Mutation update = Mutation.newUpdateBuilder("Orders")
                    .set("OrderId").to(orderId)
                    .set("Status").to("CANCELLED")
                    .set("StatusVersion").to(version + 1)
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();
            List<Mutation> mutations = new ArrayList<>();
            mutations.add(update);

            List<InventoryReleaseRequestedEvent.Item> items = loadItems(tx, orderId);
            if (items.isEmpty()) {
                // No items to release - treat as retryable failure to avoid ack gating issue
                // (order already cancelled but no items means data inconsistency or race condition)
                log.warn("CancelIfPending found no items for orderId={}, treating as RETRYABLE_FAIL", orderId);
                return new CancelOutcome(CancelResult.RETRYABLE_FAIL, expireAt);
            }

            String outboxId = UUID.randomUUID().toString();
            InventoryReleaseRequestedEvent releaseEvent = new InventoryReleaseRequestedEvent(
                    outboxId, // Use outboxId as eventId for idempotency
                    orderId,
                    "TIMEOUT",
                    items,
                    now
            );
            mutations.add(buildOutboxInsert(
                    outboxId, // Pass the same outboxId
                    "INVENTORY_RELEASE_REQUESTED",
                    orderId,
                    toJson(releaseEvent),
                    now // Pass now for NextAttemptAt
            ));

            tx.buffer(mutations);
            return new CancelOutcome(CancelResult.CANCELLED, expireAt);
        });
    }

    /**
     * Claim a batch of outbox records for publishing (with locking to prevent multi-instance duplicates).
     * Must run in a read-write transaction to atomically claim and lock records.
     */
    public List<OrderOutboxRecord> claimOutboxBatch(String publisherId, Instant now, int limit) {
        return txRunner.runReadWrite(tx -> {
            // Query NEW records that are ready to be sent (nextAttemptAt <= now)
            Statement queryStmt = Statement.newBuilder(
                            "SELECT OutboxId, EventType, AggregateId, PayloadJson, Status, Attempts, " +
                                    "NextAttemptAt, LockedBy, LockedAt, LastError, CreatedAt, UpdatedAt " +
                                    "FROM OrderOutbox " +
                                    "WHERE Status = @status AND NextAttemptAt <= @now " +
                                    "ORDER BY NextAttemptAt, CreatedAt LIMIT @limit")
                    .bind("status").to(OutboxStatus.NEW.name())
                    .bind("now").to(Timestamp.ofTimeSecondsAndNanos(now.getEpochSecond(), now.getNano()))
                    .bind("limit").to(limit)
                    .build();

            ResultSet rs = tx.executeQuery(queryStmt);
            List<OrderOutboxRecord> records = new ArrayList<>();
            List<Mutation> claimMutations = new ArrayList<>();

            while (rs.next()) {
                String outboxId = rs.getString("OutboxId");
                // Claim this record: NEW -> SENDING, set lockedBy/lockedAt
                Mutation claimMutation = Mutation.newUpdateBuilder("OrderOutbox")
                        .set("OutboxId").to(outboxId)
                        .set("Status").to(OutboxStatus.SENDING.name())
                        .set("LockedBy").to(publisherId)
                        .set("LockedAt").to(Timestamp.ofTimeSecondsAndNanos(now.getEpochSecond(), now.getNano()))
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build();
                claimMutations.add(claimMutation);

                // Build record with current values (before claim update)
                records.add(new OrderOutboxRecord(
                        outboxId,
                        rs.getString("EventType"),
                        rs.getString("AggregateId"),
                        rs.getString("PayloadJson"),
                        OutboxStatus.SENDING, // Will be SENDING after claim
                        rs.getLong("Attempts"),
                        rs.getTimestamp("NextAttemptAt").toInstant(),
                        publisherId, // lockedBy
                        now, // lockedAt
                        rs.isNull("LastError") ? null : rs.getString("LastError"),
                        rs.getTimestamp("CreatedAt").toInstant(),
                        now // updatedAt
                ));
            }

            if (!claimMutations.isEmpty()) {
                tx.buffer(claimMutations);
            }

            return records;
        });
    }

    /**
     * Mark an outbox record as successfully sent.
     * Only updates if the record is currently SENDING and locked by the specified publisher.
     */
    public boolean markSent(String outboxId, String publisherId) {
        return txRunner.runReadWrite(tx -> {
            Struct row = tx.readRow("OrderOutbox", Key.of(outboxId),
                    List.of("Status", "LockedBy"));
            if (row == null) {
                log.warn("Outbox record not found for markSent outboxId={}", outboxId);
                return false;
            }

            String status = row.getString("Status");
            String lockedBy = row.isNull("LockedBy") ? null : row.getString("LockedBy");

            // Only update if SENDING and locked by this publisher
            if (!OutboxStatus.SENDING.name().equals(status) || !publisherId.equals(lockedBy)) {
                log.debug("Outbox record not eligible for markSent outboxId={} status={} lockedBy={} publisherId={}",
                        outboxId, status, lockedBy, publisherId);
                return false;
            }

            Mutation update = Mutation.newUpdateBuilder("OrderOutbox")
                    .set("OutboxId").to(outboxId)
                    .set("Status").to(OutboxStatus.SENT.name())
                    .set("LockedBy").to((String) null)
                    .set("LockedAt").to((Timestamp) null)
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();
            tx.buffer(List.of(update));
            return true;
        });
    }

    /**
     * Mark an outbox record for retry (SENDING -> NEW) with exponential backoff.
     * If attempts exceed maxAttempts, mark as DEAD.
     * Only updates if the record is currently SENDING and locked by the specified publisher.
     */
    public boolean markRetry(String outboxId, String publisherId, String error, Instant now, int maxAttempts, long baseBackoffMs, int maxErrorLength) {
        return txRunner.runReadWrite(tx -> {
            // Read current status, attempts, and lockedBy
            Struct row = tx.readRow("OrderOutbox", Key.of(outboxId),
                    List.of("Status", "Attempts", "LockedBy"));
            if (row == null) {
                log.warn("Outbox record not found for retry outboxId={}", outboxId);
                return false;
            }

            String status = row.getString("Status");
            String lockedBy = row.isNull("LockedBy") ? null : row.getString("LockedBy");

            // Only update if SENDING and locked by this publisher
            if (!OutboxStatus.SENDING.name().equals(status) || !publisherId.equals(lockedBy)) {
                log.debug("Outbox record not eligible for markRetry outboxId={} status={} lockedBy={} publisherId={}",
                        outboxId, status, lockedBy, publisherId);
                return false;
            }

            long currentAttempts = row.getLong("Attempts");
            long newAttempts = currentAttempts + 1;

            OutboxStatus newStatus;
            Instant nextAttemptAt;

            if (newAttempts >= maxAttempts) {
                // Exceeded max attempts, mark as DEAD
                // NextAttemptAt must be NOT NULL - use a far future timestamp as sentinel value
                newStatus = OutboxStatus.DEAD;
                nextAttemptAt = now.plusSeconds(365L * 24 * 3600); // 1 year in the future as sentinel
                log.error("Outbox record exceeded max attempts, marking as DEAD outboxId={} attempts={} max={}",
                        outboxId, newAttempts, maxAttempts);
            } else {
                // Calculate exponential backoff: baseBackoffMs * 2^(attempts-1)
                long backoffMs = baseBackoffMs * (1L << (int) (newAttempts - 1));
                // Cap at 1 hour
                backoffMs = Math.min(backoffMs, 3600000L);
                nextAttemptAt = now.plusMillis(backoffMs);
                newStatus = OutboxStatus.NEW;
            }

            Mutation update = Mutation.newUpdateBuilder("OrderOutbox")
                    .set("OutboxId").to(outboxId)
                    .set("Status").to(newStatus.name())
                    .set("Attempts").to(newAttempts)
                    .set("NextAttemptAt").to(Timestamp.ofTimeSecondsAndNanos(nextAttemptAt.getEpochSecond(), nextAttemptAt.getNano()))
                    .set("LockedBy").to((String) null)
                    .set("LockedAt").to((Timestamp) null)
                    .set("LastError").to(error != null && error.length() > maxErrorLength ? error.substring(0, maxErrorLength) : error)
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();
            tx.buffer(List.of(update));
            return true;
        });
    }

    /**
     * Reclaim stale SENDING records that have been locked for too long.
     * This handles cases where a publisher crashed after claiming records.
     */
    public int reclaimStaleOutbox(Instant now, long staleMs, int limit) {
        return txRunner.runReadWrite(tx -> {
            Instant staleThreshold = now.minusMillis(staleMs);
            Timestamp staleTimestamp = Timestamp.ofTimeSecondsAndNanos(staleThreshold.getEpochSecond(), staleThreshold.getNano());

            // Query SENDING records with LockedAt < staleThreshold
            Statement queryStmt = Statement.newBuilder(
                            "SELECT OutboxId FROM OrderOutbox " +
                                    "WHERE Status = @status AND LockedAt IS NOT NULL AND LockedAt < @staleThreshold " +
                                    "ORDER BY LockedAt LIMIT @limit")
                    .bind("status").to(OutboxStatus.SENDING.name())
                    .bind("staleThreshold").to(staleTimestamp)
                    .bind("limit").to(limit)
                    .build();

            ResultSet rs = tx.executeQuery(queryStmt);
            List<String> staleIds = new ArrayList<>();
            while (rs.next()) {
                staleIds.add(rs.getString("OutboxId"));
            }

            if (staleIds.isEmpty()) {
                return 0;
            }

            // Reset these records to NEW status, clear locks, set NextAttemptAt to now
            List<Mutation> reclaimMutations = new ArrayList<>();
            Timestamp nowTimestamp = Timestamp.ofTimeSecondsAndNanos(now.getEpochSecond(), now.getNano());
            for (String outboxId : staleIds) {
                Mutation reclaim = Mutation.newUpdateBuilder("OrderOutbox")
                        .set("OutboxId").to(outboxId)
                        .set("Status").to(OutboxStatus.NEW.name())
                        .set("LockedBy").to((String) null)
                        .set("LockedAt").to((Timestamp) null)
                        .set("NextAttemptAt").to(nowTimestamp) // Make it eligible for immediate retry
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build();
                reclaimMutations.add(reclaim);
            }

            tx.buffer(reclaimMutations);
            log.info("Reclaimed {} stale SENDING outbox records", staleIds.size());
            return staleIds.size();
        });
    }

    private List<InventoryReleaseRequestedEvent.Item> loadItems(TransactionContext tx, String orderId) {
        Statement stmt = Statement.newBuilder(
                        "SELECT SkuId, Quantity FROM OrderItems WHERE OrderId = @orderId")
                .bind("orderId").to(orderId)
                .build();
        ResultSet rs = tx.executeQuery(stmt);
        List<InventoryReleaseRequestedEvent.Item> items = new ArrayList<>();
        while (rs.next()) {
            items.add(new InventoryReleaseRequestedEvent.Item(
                    rs.getString("SkuId"),
                    rs.getLong("Quantity")
            ));
        }
        return items;
    }

    private Mutation buildOutboxInsert(String outboxId, String eventType, String aggregateId, String payloadJson, Instant nextAttemptAt) {
        // NextAttemptAt must be NOT NULL
        return Mutation.newInsertBuilder("OrderOutbox")
                .set("OutboxId").to(outboxId)
                .set("EventType").to(eventType)
                .set("AggregateId").to(aggregateId)
                .set("PayloadJson").to(payloadJson)
                .set("Status").to(OutboxStatus.NEW.name())
                .set("Attempts").to(0L)
                .set("NextAttemptAt").to(Timestamp.ofTimeSecondsAndNanos(nextAttemptAt.getEpochSecond(), nextAttemptAt.getNano()))
                .set("LockedBy").to((String) null)
                .set("LockedAt").to((Timestamp) null)
                .set("LastError").to((String) null)
                .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                .build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}

