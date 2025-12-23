package com.lingxiao.inventory.infrastructure.db.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Value;
import com.lingxiao.common.db.errors.NotFoundException;
import com.lingxiao.common.db.errors.SpannerErrorTranslator;
import com.lingxiao.common.db.repo.BaseRepositorySupport;
import com.lingxiao.common.db.tx.TxRunner;
import com.lingxiao.inventory.domain.model.ReservationStatus;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class InventoryRepository extends BaseRepositorySupport {

    public InventoryRepository(DatabaseClient databaseClient, SpannerErrorTranslator translator, TxRunner txRunner) {
        super(databaseClient, translator, txRunner);
    }

    public void seed(String skuId, long onHand) {
        inReadWrite(tx -> {
            Mutation m = Mutation.newInsertOrUpdateBuilder("Inventory")
                    .set("SkuId").to(skuId)
                    .set("OnHand").to(onHand)
                    .set("Reserved").to(0L)
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();
            tx.buffer(m);
            return null;
        });
    }

    public long getAvailable(String skuId) {
        return inReadOnly(tx -> {
            Statement stmt = Statement.newBuilder(
                            "SELECT OnHand, Reserved FROM Inventory WHERE SkuId=@id")
                    .bind("id").to(skuId)
                    .build();
            try (ResultSet rs = tx.executeQuery(stmt)) {
                if (!rs.next()) {
                    throw new NotFoundException("sku not found: " + skuId);
                }
                long onHand = rs.getLong("OnHand");
                long reserved = rs.getLong("Reserved");
                return onHand - reserved;
            }
        });
    }

    public boolean reserve(String orderId, String skuId, long qty, Duration ttl) {
        Instant now = Instant.now();
        Instant expireAt = now.plus(ttl);
        return inReadWrite(tx -> {
            // idempotency: if reservation exists and status HELD/COMMITTED treat as success
            Optional<ReservationRow> existing = getReservation(tx, orderId, skuId);
            if (existing.isPresent()) {
                ReservationRow row = existing.get();
                if (row.status == ReservationStatus.HELD || row.status == ReservationStatus.COMMITTED) {
                    return true;
                }
                // if cancelled/expired fall through to attempt again
            }

            Optional<InventoryRow> invOpt = getInventory(tx, skuId);
            if (invOpt.isEmpty()) {
                return false;
            }
            InventoryRow inv = invOpt.get();
            long available = inv.onHand - inv.reserved;
            if (available < qty) {
                return false;
            }

            Mutation updateInv = Mutation.newUpdateBuilder("Inventory")
                    .set("SkuId").to(skuId)
                    .set("OnHand").to(inv.onHand)
                    .set("Reserved").to(inv.reserved + qty)
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();

            Mutation upsertRes = Mutation.newInsertOrUpdateBuilder("InventoryReservations")
                    .set("OrderId").to(orderId)
                    .set("SkuId").to(skuId)
                    .set("Qty").to(qty)
                    .set("Status").to(ReservationStatus.HELD.name())
                    .set("ExpireAt").to(com.google.cloud.Timestamp.ofTimeSecondsAndNanos(expireAt.getEpochSecond(), expireAt.getNano()))
                    .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();

            tx.buffer(updateInv);
            tx.buffer(upsertRes);
            return true;
        });
    }

    public boolean reserveBatch(String orderId, List<com.lingxiao.inventory.api.dto.ReserveItem> items, Duration ttl) {
        Instant now = Instant.now();
        Instant expireAt = now.plus(ttl);
        return inReadWrite(tx -> {
            if (items == null || items.size() != 1 || items.getFirst().qty() != 1) {
                return false;
            }
            // sum qty per sku
            java.util.Map<String, Long> requested = new java.util.HashMap<>();
            for (com.lingxiao.inventory.api.dto.ReserveItem it : items) {
                requested.merge(it.skuId(), it.qty(), Long::sum);
            }

            List<ReservationRow> existing = listReservations(tx, orderId);
            java.util.Map<String, ReservationRow> existingBySku = new java.util.HashMap<>();
            for (ReservationRow row : existing) {
                existingBySku.put(row.skuId, row);
            }

            List<InventoryRow> invRows = new java.util.ArrayList<>();
            java.util.Map<String, InventoryRow> invBySku = new java.util.HashMap<>();
            for (String skuId : requested.keySet()) {
                Optional<InventoryRow> invOpt = getInventory(tx, skuId);
                if (invOpt.isEmpty()) {
                    return false;
                }
                InventoryRow inv = invOpt.get();
                invRows.add(inv);
                invBySku.put(skuId, inv);
            }

            // check all
            java.util.List<Mutation> mutations = new java.util.ArrayList<>();
            for (var entry : requested.entrySet()) {
                String skuId = entry.getKey();
                long qty = entry.getValue();
                ReservationRow existingRes = existingBySku.get(skuId);
                if (existingRes != null && (existingRes.status == ReservationStatus.HELD || existingRes.status == ReservationStatus.COMMITTED)) {
                    // already held, skip
                    continue;
                }
                InventoryRow inv = invBySku.get(skuId);
                long available = inv.onHand - inv.reserved;
                if (available < qty) {
                    return false;
                }
                Mutation updInv = Mutation.newUpdateBuilder("Inventory")
                        .set("SkuId").to(skuId)
                        .set("OnHand").to(inv.onHand)
                        .set("Reserved").to(inv.reserved + qty)
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build();
                Mutation upsertRes = Mutation.newInsertOrUpdateBuilder("InventoryReservations")
                        .set("OrderId").to(orderId)
                        .set("SkuId").to(skuId)
                        .set("Qty").to(qty)
                        .set("Status").to(ReservationStatus.HELD.name())
                        .set("ExpireAt").to(com.google.cloud.Timestamp.ofTimeSecondsAndNanos(expireAt.getEpochSecond(), expireAt.getNano()))
                        .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build();
                mutations.add(updInv);
                mutations.add(upsertRes);
            }

            // all checks passed, apply
            for (Mutation m : mutations) {
                tx.buffer(m);
            }
            return true;
        });
    }

    public int commit(String orderId) {
        return inReadWrite(tx -> {
            List<ReservationRow> rows = listReservations(tx, orderId);
            int affected = 0;
            for (ReservationRow row : rows) {
                if (row.status != ReservationStatus.HELD) {
                    continue;
                }
                Optional<InventoryRow> invOpt = getInventory(tx, row.skuId);
                if (invOpt.isEmpty()) {
                    continue;
                }
                InventoryRow inv = invOpt.get();
                Mutation updInv = Mutation.newUpdateBuilder("Inventory")
                        .set("SkuId").to(row.skuId)
                        .set("OnHand").to(inv.onHand - row.qty)
                        .set("Reserved").to(inv.reserved - row.qty)
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build();
                Mutation updRes = Mutation.newUpdateBuilder("InventoryReservations")
                        .set("OrderId").to(orderId)
                        .set("SkuId").to(row.skuId)
                        .set("Qty").to(row.qty)
                        .set("Status").to(ReservationStatus.COMMITTED.name())
                        .set("ExpireAt").to(row.expireAt)
                        .set("CreatedAt").to(row.createdAt)
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build();
                tx.buffer(updInv);
                tx.buffer(updRes);
                affected++;
            }
            return affected;
        });
    }

    public int release(String orderId) {
        return inReadWrite(tx -> {
            List<ReservationRow> rows = listReservations(tx, orderId);
            int affected = 0;
            for (ReservationRow row : rows) {
                if (row.status != ReservationStatus.HELD) {
                    continue;
                }
                Optional<InventoryRow> invOpt = getInventory(tx, row.skuId);
                if (invOpt.isEmpty()) {
                    continue;
                }
                InventoryRow inv = invOpt.get();
                Mutation updInv = Mutation.newUpdateBuilder("Inventory")
                        .set("SkuId").to(row.skuId)
                        .set("OnHand").to(inv.onHand)
                        .set("Reserved").to(inv.reserved - row.qty)
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build();
                Mutation updRes = Mutation.newUpdateBuilder("InventoryReservations")
                        .set("OrderId").to(orderId)
                        .set("SkuId").to(row.skuId)
                        .set("Qty").to(row.qty)
                        .set("Status").to(ReservationStatus.CANCELLED.name())
                        .set("ExpireAt").to(row.expireAt)
                        .set("CreatedAt").to(row.createdAt)
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build();
                tx.buffer(updInv);
                tx.buffer(updRes);
                affected++;
            }
            return affected;
        });
    }

    private Optional<InventoryRow> getInventory(com.google.cloud.spanner.ReadContext tx, String skuId) {
        Statement stmt = Statement.newBuilder(
                        "SELECT OnHand, Reserved FROM Inventory WHERE SkuId=@id")
                .bind("id").to(skuId)
                .build();
        try (ResultSet rs = tx.executeQuery(stmt)) {
            if (rs.next()) {
                return Optional.of(new InventoryRow(rs.getLong("OnHand"), rs.getLong("Reserved")));
            }
            return Optional.empty();
        }
    }

    private Optional<ReservationRow> getReservation(com.google.cloud.spanner.ReadContext tx, String orderId, String skuId) {
        Statement stmt = Statement.newBuilder(
                        "SELECT Qty, Status, ExpireAt, CreatedAt, UpdatedAt FROM InventoryReservations WHERE OrderId=@o AND SkuId=@s")
                .bind("o").to(orderId)
                .bind("s").to(skuId)
                .build();
        try (ResultSet rs = tx.executeQuery(stmt)) {
            if (rs.next()) {
                return Optional.of(mapReservation(rs, orderId, skuId));
            }
            return Optional.empty();
        }
    }

    private List<ReservationRow> listReservations(com.google.cloud.spanner.ReadContext tx, String orderId) {
        Statement stmt = Statement.newBuilder(
                        "SELECT OrderId, SkuId, Qty, Status, ExpireAt, CreatedAt, UpdatedAt FROM InventoryReservations WHERE OrderId=@o")
                .bind("o").to(orderId)
                .build();
        try (ResultSet rs = tx.executeQuery(stmt)) {
            List<ReservationRow> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapReservation(rs, rs.getString("OrderId"), rs.getString("SkuId")));
            }
            return list;
        }
    }

    private ReservationRow mapReservation(ResultSet rs, String orderId, String skuId) {
        return new ReservationRow(
                orderId,
                skuId,
                rs.getLong("Qty"),
                ReservationStatus.valueOf(rs.getString("Status")),
                rs.getTimestamp("ExpireAt"),
                rs.getTimestamp("CreatedAt"),
                rs.getTimestamp("UpdatedAt")
        );
    }

    private record InventoryRow(long onHand, long reserved) {}

    private record ReservationRow(String orderId, String skuId, long qty, ReservationStatus status,
                                  com.google.cloud.Timestamp expireAt,
                                  com.google.cloud.Timestamp createdAt,
                                  com.google.cloud.Timestamp updatedAt) {}
}

