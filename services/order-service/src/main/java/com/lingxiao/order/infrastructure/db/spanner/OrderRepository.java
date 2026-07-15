package com.lingxiao.order.infrastructure.db.spanner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.cloud.Timestamp;
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
import com.lingxiao.contracts.events.OrderLineItem;
import com.lingxiao.contracts.events.OrderPaidEvent;
import com.lingxiao.contracts.events.PaymentSucceededEvent;
import com.lingxiao.order.infrastructure.db.spanner.model.CancelOutcome;
import com.lingxiao.order.infrastructure.db.spanner.model.CancelResult;
import com.lingxiao.order.infrastructure.db.spanner.model.OrderOutboxRecord;
import com.lingxiao.order.infrastructure.db.spanner.model.OutboxStatus;
import com.lingxiao.order.application.OrderSummary;
import com.lingxiao.order.messaging.OrderNotFoundForPaymentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OrderRepository {

    private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);

    private final TxRunner txRunner;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final long timeoutGraceMs;

    public OrderRepository(TxRunner txRunner,
                           @org.springframework.beans.factory.annotation.Value("${order.timeout.grace-period-ms:60000}") long timeoutGraceMs) {
        this.txRunner = txRunner;
        this.timeoutGraceMs = timeoutGraceMs;
    }

    /**
     * Returns the public fields of a flash-sale order. The reservation event creates one
     * OrderItems row with LineId=1 in the same transaction as the Orders row.
     */
    public Optional<OrderSummary> findSummary(String orderId) {
        return txRunner.runReadOnly(tx -> {
            Statement statement = Statement.newBuilder("""
                    SELECT o.OrderId, o.UserId, o.Status, o.CreatedAt, o.UpdatedAt, i.SkuId, i.Quantity
                    FROM Orders o
                    JOIN OrderItems i ON o.OrderId = i.OrderId
                    WHERE o.OrderId = @orderId AND i.LineId = 1
                    """)
                    .bind("orderId").to(orderId)
                    .build();
            try (ResultSet resultSet = tx.executeQuery(statement)) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OrderSummary(
                        resultSet.getString("OrderId"),
                        resultSet.getString("SkuId"),
                        resultSet.getString("UserId"),
                        resultSet.getLong("Quantity"),
                        resultSet.getString("Status"),
                        Instant.ofEpochSecond(resultSet.getTimestamp("CreatedAt").getSeconds(), resultSet.getTimestamp("CreatedAt").getNanos()),
                        Instant.ofEpochSecond(resultSet.getTimestamp("UpdatedAt").getSeconds(), resultSet.getTimestamp("UpdatedAt").getNanos())
                ));
            }
        });
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

            PendingPayment pending = getPendingPayment(tx, event.orderId());

            Instant expireAt = event.expireAt() != null ? event.expireAt() : event.occurredAt().plusSeconds(300);
            long subtotal = event.priceCents() * event.qty();
            long discount = 0;
            long tax = 0;
            long shipping = 0;
            long total = subtotal - discount + tax + shipping;

            boolean pendingPaid = pending != null && "PENDING".equals(pending.status());
            boolean latePayment = pendingPaid && pending.paidAt() != null && pending.paidAt().isAfter(expireAt);
            boolean alreadyPaid = pendingPaid && !latePayment;
            String status = alreadyPaid ? "PAID" : "PENDING_PAYMENT";
            long statusVersion = alreadyPaid ? 2L : 1L;

            Mutation orderMutation = Mutation.newInsertBuilder("Orders")
                    .set("OrderId").to(event.orderId())
                    .set("UserId").to(event.userId())
                    .set("Status").to(status)
                    .set("StatusVersion").to(statusVersion)
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
            Instant now = Instant.now();

            if (latePayment) {
                mutations.add(Mutation.newUpdateBuilder("PendingPayments")
                        .set("OrderId").to(event.orderId())
                        .set("Status").to("REFUND_REQUIRED")
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build());
                // still schedule timeout handling; order stays PENDING_PAYMENT
                String timeoutOutboxId = UUID.randomUUID().toString();
                mutations.add(buildOutboxInsert(
                        timeoutOutboxId,
                        "ORDER_TIMEOUT_SCHEDULED",
                        event.orderId(),
                        toJson(new com.lingxiao.contracts.events.OrderTimeoutScheduledEvent(event.orderId(), expireAt)),
                        now
                ));
            } else if (alreadyPaid) {
                // Payment arrived before order creation: converge in the same transaction
                Instant paidAt = pending.paidAt() != null ? pending.paidAt() : now;
                OrderPaidEvent paidEvent = new OrderPaidEvent(
                        UUID.randomUUID().toString(),
                        event.orderId(),
                        paidAt,
                        List.of(new OrderLineItem(event.skuId(), event.qty()))
                );
                mutations.add(buildOutboxInsert(
                        UUID.randomUUID().toString(),
                        "ORDER_PAID",
                        event.orderId(),
                        toJson(paidEvent),
                        now
                ));
                mutations.add(Mutation.newUpdateBuilder("PendingPayments")
                        .set("OrderId").to(event.orderId())
                        .set("Status").to("APPLIED")
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build());
            } else {
                String timeoutOutboxId = UUID.randomUUID().toString();
                mutations.add(buildOutboxInsert(
                        timeoutOutboxId,
                        "ORDER_TIMEOUT_SCHEDULED",
                        event.orderId(),
                        toJson(new com.lingxiao.contracts.events.OrderTimeoutScheduledEvent(event.orderId(), expireAt)),
                        now
                ));
            }
            tx.buffer(mutations);
            return null;
        });
    }

    /**
     * Handle payment succeeded with out-of-order tolerance:
     * - If order exists: mark PAID (if eligible) and publish ORDER_PAID outbox.
     * - If order doesn't exist: upsert into PendingPayments for later reconciliation.
     */
    public void handlePaymentSucceeded(PaymentSucceededEvent event) {
        boolean needsRetry = txRunner.runReadWrite(tx -> {
            Instant paidAt = event.paidAt() != null ? event.paidAt() : Instant.now();

            Struct orderRow = tx.readRow("Orders", Key.of(event.orderId()),
                    List.of("Status", "StatusVersion", "ExpireAt"));
            if (orderRow == null) {
                upsertPendingPayment(tx, event, paidAt);
                return true; // order missing, request retry after commit
            }

            String status = orderRow.getString("Status");
            long version = orderRow.getLong("StatusVersion");

            Instant expireAt = Instant.ofEpochSecond(orderRow.getTimestamp("ExpireAt").getSeconds(),
                    orderRow.getTimestamp("ExpireAt").getNanos());
            if (expireAt.isBefore(paidAt) || "CANCELLED".equals(status)) {
                markPendingRefundRequired(tx, event, paidAt);
                return false;
            }

            if ("PENDING_PAYMENT".equals(status)) {
                Mutation update = Mutation.newUpdateBuilder("Orders")
                        .set("OrderId").to(event.orderId())
                        .set("Status").to("PAID")
                        .set("StatusVersion").to(version + 1)
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build();
                tx.buffer(update);
            }

            // If pending payment was already applied during order creation, don't republish ORDER_PAID.
            Struct pending = tx.readRow("PendingPayments", Key.of(event.orderId()), List.of("PaymentId", "Status"));
            boolean pendingApplied = pending != null
                    && "APPLIED".equals(pending.getString("Status"))
                    && event.paymentId().equals(pending.getString("PaymentId"));

            if (!pendingApplied) {
                // Publish ORDER_PAID to outbox (idempotent consumers are expected).
                List<OrderLineItem> items = loadOrderLineItems(tx, event.orderId());
                if (!items.isEmpty()) {
                    OrderPaidEvent paidEvent = new OrderPaidEvent(
                            UUID.randomUUID().toString(),
                            event.orderId(),
                            paidAt,
                            items
                    );
                    tx.buffer(buildOutboxInsert(
                            UUID.randomUUID().toString(),
                            "ORDER_PAID",
                            event.orderId(),
                            toJson(paidEvent),
                            Instant.now()
                    ));
                }
            }

            // Persist payment as applied for gating/idempotency (do NOT affect the "order missing" path).
            upsertAppliedPayment(tx, event, paidAt);
            return false;
        });
        if (needsRetry) {
            throw new OrderNotFoundForPaymentException(event.orderId());
        }
    }

    public enum PendingPaymentReconcileResult {
        ORDER_MISSING,
        NO_PENDING,
        ALREADY_FINAL,
        APPLIED,
        REFUND_REQUIRED
    }

    /**
     * Reconcile a previously stored PendingPayments=PENDING record once the order exists.
     * This is used by the non-blocking reconcile queue to avoid blocking Kafka partitions.
     */
    public PendingPaymentReconcileResult reconcilePendingPayment(String orderId, Instant now) {
        return txRunner.runReadWrite(tx -> {
            Struct pendingRow = tx.readRow("PendingPayments", Key.of(orderId),
                    List.of("PaymentId", "AmountCents", "Currency", "PaidAt", "Status"));
            if (pendingRow == null) {
                return PendingPaymentReconcileResult.NO_PENDING;
            }
            String pendingStatus = pendingRow.getString("Status");
            if (!"PENDING".equals(pendingStatus)) {
                return PendingPaymentReconcileResult.ALREADY_FINAL;
            }

            Struct orderRow = tx.readRow("Orders", Key.of(orderId),
                    List.of("Status", "StatusVersion", "ExpireAt"));
            if (orderRow == null) {
                return PendingPaymentReconcileResult.ORDER_MISSING;
            }

            String orderStatus = orderRow.getString("Status");
            Instant expireAt = Instant.ofEpochSecond(orderRow.getTimestamp("ExpireAt").getSeconds(),
                    orderRow.getTimestamp("ExpireAt").getNanos());

            Instant paidAt = Instant.ofEpochSecond(pendingRow.getTimestamp("PaidAt").getSeconds(),
                    pendingRow.getTimestamp("PaidAt").getNanos());

            // late payment or already cancelled -> refund required
            if (expireAt.isBefore(paidAt) || "CANCELLED".equals(orderStatus)) {
                tx.buffer(Mutation.newUpdateBuilder("PendingPayments")
                        .set("OrderId").to(orderId)
                        .set("Status").to("REFUND_REQUIRED")
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build());
                return PendingPaymentReconcileResult.REFUND_REQUIRED;
            }

            boolean transitionedToPaid = false;
            if ("PENDING_PAYMENT".equals(orderStatus)) {
                long version = orderRow.getLong("StatusVersion");
                tx.buffer(Mutation.newUpdateBuilder("Orders")
                        .set("OrderId").to(orderId)
                        .set("Status").to("PAID")
                        .set("StatusVersion").to(version + 1)
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build());
                transitionedToPaid = true;
            }

            // publish ORDER_PAID only when we transition to PAID in this reconcile
            if (transitionedToPaid) {
                List<OrderLineItem> items = loadOrderLineItems(tx, orderId);
                if (!items.isEmpty()) {
                    OrderPaidEvent paidEvent = new OrderPaidEvent(
                            UUID.randomUUID().toString(),
                            orderId,
                            paidAt,
                            items
                    );
                    tx.buffer(buildOutboxInsert(
                            UUID.randomUUID().toString(),
                            "ORDER_PAID",
                            orderId,
                            toJson(paidEvent),
                            now != null ? now : Instant.now()
                    ));
                }
            }

            tx.buffer(Mutation.newUpdateBuilder("PendingPayments")
                    .set("OrderId").to(orderId)
                    .set("Status").to("APPLIED")
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build());

            return PendingPaymentReconcileResult.APPLIED;
        });
    }

    private void upsertAppliedPayment(TransactionContext tx, PaymentSucceededEvent event, Instant paidAt) {
        String raw = toJson(event);
        Struct existing = tx.readRow("PendingPayments", Key.of(event.orderId()), List.of("Status", "PaymentId"));
        if (existing != null) {
            String s = existing.getString("Status");
            if ("REFUND_REQUIRED".equals(s)) {
                return; // don't override refund-required
            }
            if ("APPLIED".equals(s) && event.paymentId().equals(existing.getString("PaymentId"))) {
                return; // already applied
            }
        }
        Mutation m = Mutation.newInsertOrUpdateBuilder("PendingPayments")
                .set("OrderId").to(event.orderId())
                .set("PaymentId").to(event.paymentId())
                .set("AmountCents").to(event.amountCents())
                .set("Currency").to(event.currency())
                .set("PaidAt").to(Timestamp.ofTimeSecondsAndNanos(paidAt.getEpochSecond(), paidAt.getNano()))
                .set("Status").to("APPLIED")
                .set("RawEventJson").to(raw)
                .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                .build();
        tx.buffer(m);
    }

    private void upsertPendingPayment(TransactionContext tx, PaymentSucceededEvent event, Instant paidAt) {
        String raw = toJson(event);
        Struct existing = tx.readRow("PendingPayments", Key.of(event.orderId()), List.of("Status"));
        if (existing != null) {
            String s = existing.getString("Status");
            if (!"PENDING".equals(s)) {
                // don't downgrade APPLIED/REFUND_REQUIRED
                return;
            }
        }
        Mutation m = Mutation.newInsertOrUpdateBuilder("PendingPayments")
                .set("OrderId").to(event.orderId())
                .set("PaymentId").to(event.paymentId())
                .set("AmountCents").to(event.amountCents())
                .set("Currency").to(event.currency())
                .set("PaidAt").to(Timestamp.ofTimeSecondsAndNanos(paidAt.getEpochSecond(), paidAt.getNano()))
                .set("Status").to("PENDING")
                .set("RawEventJson").to(raw)
                .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                .build();
        tx.buffer(m);
    }

    private void markPendingRefundRequired(TransactionContext tx, PaymentSucceededEvent event, Instant paidAt) {
        String raw = toJson(event);
        Mutation m = Mutation.newInsertOrUpdateBuilder("PendingPayments")
                .set("OrderId").to(event.orderId())
                .set("PaymentId").to(event.paymentId())
                .set("AmountCents").to(event.amountCents())
                .set("Currency").to(event.currency())
                .set("PaidAt").to(Timestamp.ofTimeSecondsAndNanos(paidAt.getEpochSecond(), paidAt.getNano()))
                .set("Status").to("REFUND_REQUIRED")
                .set("RawEventJson").to(raw)
                .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                .build();
        tx.buffer(m);
    }

    private PendingPayment getPendingPayment(TransactionContext tx, String orderId) {
        Struct row = tx.readRow("PendingPayments", Key.of(orderId),
                List.of("PaymentId", "AmountCents", "Currency", "PaidAt", "Status"));
        if (row == null) return null;
        Instant paidAt = Instant.ofEpochSecond(row.getTimestamp("PaidAt").getSeconds(), row.getTimestamp("PaidAt").getNanos());
        return new PendingPayment(
                row.getString("PaymentId"),
                row.getLong("AmountCents"),
                row.getString("Currency"),
                paidAt,
                row.getString("Status")
        );
    }

    private record PendingPayment(String paymentId, long amountCents, String currency, Instant paidAt, String status) {}

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
            Instant effectiveExpireAt = expireAt.plusMillis(timeoutGraceMs);
            if (effectiveExpireAt.isAfter(now)) {
                return new CancelOutcome(CancelResult.NOT_EXPIRED_YET, effectiveExpireAt);
            }

            // Final gate: if we already saw a valid payment in inbox, converge instead of cancelling.
            PendingPayment pending = getPendingPayment(tx, orderId);
            if (pending != null && ("PENDING".equals(pending.status()) || "APPLIED".equals(pending.status()))) {
                boolean late = pending.paidAt() != null && pending.paidAt().isAfter(expireAt);
                if (!late) {
                    if ("PENDING_PAYMENT".equals(status)) {
                        Mutation paid = Mutation.newUpdateBuilder("Orders")
                                .set("OrderId").to(orderId)
                                .set("Status").to("PAID")
                                .set("StatusVersion").to(row.getLong("StatusVersion") + 1)
                                .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                                .build();
                        tx.buffer(paid);
                    }
                    tx.buffer(Mutation.newUpdateBuilder("PendingPayments")
                            .set("OrderId").to(orderId)
                            .set("Status").to("APPLIED")
                            .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                            .build());
                    return new CancelOutcome(CancelResult.ALREADY_FINAL, effectiveExpireAt);
                }
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
                        OutboxStatus.SENDING,
                        (rs.isNull("Attempts") ? 0 : Math.toIntExact(rs.getLong("Attempts"))),
                        toInstant(rs.getTimestamp("NextAttemptAt")),
                        publisherId,
                        now,
                        rs.isNull("LastError") ? null : rs.getString("LastError"),
                        toInstant(rs.getTimestamp("CreatedAt")),
                        now
                ));
            }

            if (!claimMutations.isEmpty()) {
                tx.buffer(claimMutations);
            }

            return records;
        });
    }

    private static Instant toInstant(Timestamp ts) {
        if (ts == null) return null;
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
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

    /**
     * Load order items as OrderLineItem for OrderPaidEvent.
     */
    private List<com.lingxiao.contracts.events.OrderLineItem> loadOrderLineItems(TransactionContext tx, String orderId) {
        Statement stmt = Statement.newBuilder(
                        "SELECT SkuId, Quantity FROM OrderItems WHERE OrderId = @orderId")
                .bind("orderId").to(orderId)
                .build();
        ResultSet rs = tx.executeQuery(stmt);
        List<com.lingxiao.contracts.events.OrderLineItem> items = new ArrayList<>();
        while (rs.next()) {
            items.add(new com.lingxiao.contracts.events.OrderLineItem(
                    rs.getString("SkuId"),
                    rs.getLong("Quantity")
            ));
        }
        return items;
    }

    /**
     * Mark order as PAID if it's currently PENDING_PAYMENT.
     * Returns true if the update was successful, false if order was not in PENDING_PAYMENT status.
     */
    public boolean markPaid(String orderId, Instant paidAt) {
        return txRunner.runReadWrite(tx -> {
            Struct row = tx.readRow("Orders", Key.of(orderId),
                    List.of("Status", "StatusVersion"));
            if (row == null) {
                log.warn("Order not found for markPaid orderId={}", orderId);
                return false;
            }

            String status = row.getString("Status");
            if (!"PENDING_PAYMENT".equals(status)) {
                log.debug("Order not in PENDING_PAYMENT status, cannot mark as PAID orderId={} status={}", orderId, status);
                return false;
            }

            long version = row.getLong("StatusVersion");

            Mutation update = Mutation.newUpdateBuilder("Orders")
                    .set("OrderId").to(orderId)
                    .set("Status").to("PAID")
                    .set("StatusVersion").to(version + 1)
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();
            tx.buffer(update);
            return true;
        });
    }

    /**
     * Publish OrderPaidEvent to outbox within the same transaction.
     */
    public void publishOrderPaidEvent(String orderId, Instant paidAt) {
        txRunner.runReadWrite(tx -> {
            List<com.lingxiao.contracts.events.OrderLineItem> items = loadOrderLineItems(tx, orderId);
            if (items.isEmpty()) {
                log.warn("Order has no items, skipping OrderPaidEvent orderId={}", orderId);
                return null;
            }

            String eventId = UUID.randomUUID().toString();
            com.lingxiao.contracts.events.OrderPaidEvent event = new com.lingxiao.contracts.events.OrderPaidEvent(
                    eventId,
                    orderId,
                    paidAt,
                    items
            );

            String outboxId = UUID.randomUUID().toString();
            String payloadJson = toJson(event);
            Instant nextAttemptAt = Instant.now();

            Mutation outboxMutation = buildOutboxInsert(
                    outboxId,
                    "ORDER_PAID",
                    orderId,
                    payloadJson,
                    nextAttemptAt
            );
            tx.buffer(outboxMutation);
            return null;
        });
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

