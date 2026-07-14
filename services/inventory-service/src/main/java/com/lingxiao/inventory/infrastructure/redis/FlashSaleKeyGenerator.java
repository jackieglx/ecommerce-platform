package com.lingxiao.inventory.infrastructure.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class FlashSaleKeyGenerator {

    private final String activityId;
    private final int shardCount;
    private final String stockPrefix;
    private final String buyersPrefix;
    private final String orderPrefix;
    private final String streamPrefix;
    private final String snapshotPrefix;
    private final String pricePrefix;

    public FlashSaleKeyGenerator(
            @Value("${inventory.flashsale.activity-id:fs}") String activityId,
            @Value("${inventory.flashsale.stream-shards:8}") int shardCount,
            @Value("${inventory.flashsale.stock-prefix:fs:stock:}") String stockPrefix,
            @Value("${inventory.flashsale.buyers-prefix:fs:buyers:}") String buyersPrefix,
            @Value("${inventory.flashsale.idempotency-prefix:fs:order:}") String orderPrefix,
            @Value("${inventory.flashsale.outbox.stream-prefix:fs:stream:}") String streamPrefix,
            @Value("${inventory.flashsale.snapshot-prefix:fs:snap:}") String snapshotPrefix,
            @Value("${inventory.flashsale.price-prefix:fs:price:}") String pricePrefix
    ) {
        if (!StringUtils.hasText(activityId)) {
            throw new IllegalArgumentException("activityId must not be blank");
        }
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        this.activityId = activityId;
        this.shardCount = shardCount;
        this.stockPrefix = stockPrefix;
        this.buyersPrefix = buyersPrefix;
        this.orderPrefix = orderPrefix;
        this.streamPrefix = streamPrefix;
        this.snapshotPrefix = snapshotPrefix;
        this.pricePrefix = pricePrefix;
    }

    public int shardForSku(String skuId) {
        if (!StringUtils.hasText(skuId)) {
            throw new IllegalArgumentException("skuId must not be blank");
        }
        return Math.floorMod(skuId.hashCode(), shardCount);
    }

    public String shardIdForSku(String skuId) {
        return String.format("%02d", shardForSku(skuId));
    }

    public String shardTag(String skuId) {
        return "{" + activityId + ":" + shardIdForSku(skuId) + "}";
    }

    public String stockKey(String skuId) {
        return stockPrefix + shardTag(skuId) + ":sku:" + skuId;
    }

    public String buyersKey(String skuId) {
        return buyersPrefix + shardTag(skuId) + ":sku:" + skuId;
    }

    /**
     * Price hash key, preheated before the activity. Shares the same hash-tag as
     * {@link #stockKey(String)} so a single Lua call can read both stock and price.
     * Format: fs:price:{activityId:shard}:sku:${skuId}, hash fields: priceCents, currency.
     */
    public String priceKey(String skuId) {
        return pricePrefix + shardTag(skuId) + ":sku:" + skuId;
    }

    public String orderKey(String orderId, String skuId) {
        return orderPrefix + shardTag(skuId) + ":" + orderId;
    }

    public String streamKey(String skuId) {
        return streamPrefix + shardTag(skuId);
    }

    /**
     * Snapshot key must be derivable from orderId alone (payment-service only has orderId).
     * Format: fs:snap:{activityId:shard}:order:${orderId}
     */
    public String snapshotKey(String orderId) {
        return snapshotPrefix + shardTagFromOrderId(orderId) + ":order:" + orderId;
    }

    /**
     * OrderId format: o-fs-${activityId}-${shardId}-${uuid}
     */
    public String generateOrderIdForSku(String skuId) {
        String shardId = shardIdForSku(skuId);
        return "o-fs-" + activityId + "-" + shardId + "-" + java.util.UUID.randomUUID();
    }

    /**
     * Extract shard tag from orderId.
     * Expected orderId format: o-fs-${activityId}-${shardId}-${uuid}
     */
    public String shardTagFromOrderId(String orderId) {
        if (!StringUtils.hasText(orderId)) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        // split with limit to keep UUID part intact even if it contains '-'
        String[] parts = orderId.split("-", 6);
        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid orderId format, expected o-fs-{activityId}-{shardId}-..., got=" + orderId);
        }
        String prefix0 = parts[0];
        String prefix1 = parts[1];
        String parsedActivityId = parts[2];
        String shardId = parts[3];
        if (!"o".equals(prefix0) || !"fs".equals(prefix1)) {
            throw new IllegalArgumentException("Invalid orderId prefix, got=" + orderId);
        }
        if (!StringUtils.hasText(parsedActivityId) || !StringUtils.hasText(shardId)) {
            throw new IllegalArgumentException("Invalid orderId activityId/shardId, got=" + orderId);
        }
        return "{" + parsedActivityId + ":" + shardId + "}";
    }

    public List<StreamShard> streamShards() {
        List<StreamShard> shards = new ArrayList<>();
        for (int shard = 0; shard < shardCount; shard++) {
            String shardId = String.format("%02d", shard);
            String tag = "{" + activityId + ":" + shardId + "}";
            shards.add(new StreamShard(shardId, streamPrefix + tag));
        }
        return shards;
    }

    public record StreamShard(String shardId, String streamKey) {}
}
