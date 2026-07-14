package com.lingxiao.payment.infrastructure.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderSnapshotRepository {

    private final StringRedisTemplate redisTemplate;
    private final String snapshotPrefix;

    public OrderSnapshotRepository(StringRedisTemplate redisTemplate,
                                   @Value("${payment.snapshot-prefix:fs:snap:}") String snapshotPrefix) {
        this.redisTemplate = redisTemplate;
        this.snapshotPrefix = snapshotPrefix;
    }

    public Optional<OrderSnapshot> get(String orderId) {
        if (!StringUtils.hasText(orderId)) {
            return Optional.empty();
        }
        OrderIdParts parts;
        try {
            parts = OrderIdParts.parse(orderId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String key = snapshotPrefix + "{" + parts.activityId + ":" + parts.shardId + "}" + ":order:" + orderId;
        List<Object> values = redisTemplate.opsForHash().multiGet(key,
                List.of("orderId", "userId", "skuId", "qty", "priceCents", "currency", "occurredAt", "expireAt"));
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        try {
            String oid = toStr(values, 0);
            String userId = toStr(values, 1);
            String skuId = toStr(values, 2);
            String qtyStr = toStr(values, 3);
            String priceStr = toStr(values, 4);
            String currency = toStr(values, 5);
            String occurredAt = toStr(values, 6);
            String expireAt = toStr(values, 7);

            if (!StringUtils.hasText(oid) || !StringUtils.hasText(userId) || !StringUtils.hasText(skuId)
                    || !StringUtils.hasText(qtyStr) || !StringUtils.hasText(priceStr) || !StringUtils.hasText(currency)) {
                return Optional.empty();
            }
            return Optional.of(new OrderSnapshot(
                    oid,
                    userId,
                    skuId,
                    Long.parseLong(qtyStr),
                    Long.parseLong(priceStr),
                    currency,
                    parseInstant(occurredAt),
                    parseInstant(expireAt)
            ));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public OrderSnapshot getRequired(String orderId) {
        return get(orderId).orElseThrow(() -> new IllegalStateException("order snapshot not found or expired for orderId=" + orderId));
    }

    private static String toStr(List<Object> values, int idx) {
        if (idx < 0 || idx >= values.size()) return null;
        Object v = values.get(idx);
        return v == null ? null : v.toString();
    }

    private static Instant parseInstant(String s) {
        if (!StringUtils.hasText(s)) return null;
        return Instant.parse(s);
    }

    private static final class OrderIdParts {
        private final String activityId;
        private final String shardId;

        private OrderIdParts(String activityId, String shardId) {
            this.activityId = activityId;
            this.shardId = shardId;
        }

        private static OrderIdParts parse(String orderId) {
            // Expected: o-fs-${activityId}-${shardId}-${uuid}
            String[] parts = orderId.split("-", 6);
            if (parts.length < 5) {
                throw new IllegalArgumentException("Invalid orderId format: " + orderId);
            }
            if (!"o".equals(parts[0]) || !"fs".equals(parts[1])) {
                throw new IllegalArgumentException("Invalid orderId prefix: " + orderId);
            }
            String activityId = parts[2];
            String shardId = parts[3];
            if (!StringUtils.hasText(activityId) || !StringUtils.hasText(shardId)) {
                throw new IllegalArgumentException("Invalid orderId parts: " + orderId);
            }
            return new OrderIdParts(activityId, shardId);
        }
    }

    public record OrderSnapshot(
            String orderId,
            String userId,
            String skuId,
            long qty,
            long priceCents,
            String currency,
            Instant occurredAt,
            Instant expireAt
    ) {}
}

