package com.lingxiao.inventory.application;

import com.lingxiao.inventory.infrastructure.redis.FlashSaleRedisRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class FlashSaleAppService {

    private final FlashSaleRedisRepository redisRepo;
    private final String stockPrefix;
    private final String buyersPrefix;
    private final String orderPrefix;
    private final String streamKey;
    private final String hashTag;
    private final Duration orderTtl;

    public FlashSaleAppService(FlashSaleRedisRepository redisRepo,
                               @Value("${inventory.flashsale.stock-prefix:fs:stock:}") String stockPrefix,
                               @Value("${inventory.flashsale.buyers-prefix:fs:buyers:}") String buyersPrefix,
                               @Value("${inventory.flashsale.idempotency-prefix:fs:order:}") String orderPrefix,
                               @Value("${inventory.flashsale.outbox.stream-key:fs:outbox:flashsale-reserved}") String streamKey,
                               @Value("${inventory.flashsale.hash-tag:fs}") String hashTag,
                               @Value("${inventory.reservation.ttl:PT15M}") Duration orderTtl) {
        this.redisRepo = redisRepo;
        this.stockPrefix = stockPrefix;
        this.buyersPrefix = buyersPrefix;
        this.orderPrefix = orderPrefix;
        this.streamKey = streamKey;
        this.hashTag = hashTag;
        this.orderTtl = orderTtl;
    }

    public FlashSaleResult reserve(String orderId, String skuId, String userId, long qty) {
        // Use a constant hash tag to keep all keys in the same slot (cluster-safe)
        String tag = "{" + hashTag + "}";
        String stockKey = stockPrefix + tag + ":" + skuId;
        String buyersKey = buyersPrefix + tag + ":" + skuId;
        String orderKey = orderPrefix + tag + ":" + orderId;

        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();

        long res = redisRepo.execute(stockKey, buyersKey, orderKey, streamKey, userId, qty, orderTtl,
                eventId, orderId, skuId, occurredAt.toString());
        FlashSaleResult result;
        if (res == 1) {
            result = new FlashSaleResult(true, false, false);
        } else if (res == 0) {
            result = new FlashSaleResult(false, false, true);
        } else if (res == -1) {
            result = new FlashSaleResult(false, true, false);
        } else {
            result = new FlashSaleResult(false, false, false);
        }
        return result;
    }

    public record FlashSaleResult(boolean success, boolean duplicate, boolean insufficient) {}
}

