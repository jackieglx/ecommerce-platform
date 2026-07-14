package com.lingxiao.inventory.application;

import com.lingxiao.inventory.infrastructure.redis.FlashSaleRedisRepository;
import com.lingxiao.inventory.infrastructure.redis.FlashSaleKeyGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class FlashSaleAppService {

    private final FlashSaleRedisRepository redisRepo;
    private final FlashSaleKeyGenerator keyGenerator;
    private final Duration orderTtl;
    private final Duration paymentTimeout;
    private final Duration orderKeyTtl;
    private final Duration snapshotTtl;
    private final Duration buyersTtl;

    public FlashSaleAppService(FlashSaleRedisRepository redisRepo,
                               FlashSaleKeyGenerator keyGenerator,
                               @Value("${inventory.reservation.ttl:PT15M}") Duration orderTtl,
                               @Value("${inventory.flashsale.payment-timeout:PT5M}") Duration paymentTimeout,
                               @Value("${inventory.flashsale.order-key-ttl:PT24H}") Duration orderKeyTtl,
                               @Value("${inventory.flashsale.snapshot-ttl:PT17M}") Duration snapshotTtl,
                               @Value("${inventory.flashsale.buyers-ttl:P30D}") Duration buyersTtl) {
        this.redisRepo = redisRepo;
        this.keyGenerator = keyGenerator;
        this.orderTtl = orderTtl;
        this.paymentTimeout = paymentTimeout;
        this.orderKeyTtl = orderKeyTtl;
        this.snapshotTtl = snapshotTtl;
        this.buyersTtl = buyersTtl;
    }

    public FlashSaleResult reserve(String orderId,
                                   String skuId,
                                   String userId,
                                   long qty) {
        String stockKey = keyGenerator.stockKey(skuId);
        String buyersKey = keyGenerator.buyersKey(skuId);
        String orderKey = keyGenerator.orderKey(orderId, skuId);
        String streamKey = keyGenerator.streamKey(skuId);
        String snapshotKey = keyGenerator.snapshotKey(orderId);
        String priceKey = keyGenerator.priceKey(skuId);

        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        Instant expireAt = occurredAt.plus(paymentTimeout);
        long orderKeyTtlSeconds = Math.max(orderTtl.toSeconds(), orderKeyTtl.toSeconds());

        long res = redisRepo.execute(
                stockKey, buyersKey, orderKey, streamKey, snapshotKey, priceKey,
                userId, qty, Duration.ofSeconds(orderKeyTtlSeconds),
                snapshotTtl, buyersTtl,
                eventId, orderId, skuId, occurredAt.toString(),
                expireAt.toString()
        );
        FlashSaleResult result;
        if (res == 1) {
            result = new FlashSaleResult(true, false, false, expireAt);
        } else if (res == 0) {
            result = new FlashSaleResult(false, false, true, expireAt);
        } else if (res == -1) {
            result = new FlashSaleResult(false, true, false, expireAt);
        } else {
            // -3 = price not preheated; -99 = null result. Both surface as FAILED.
            result = new FlashSaleResult(false, false, false, expireAt);
        }
        return result;
    }

    public long releaseRedisReservation(String orderId, String skuId, long qty) {
        String stockKey = keyGenerator.stockKey(skuId);
        String buyersKey = keyGenerator.buyersKey(skuId);
        String orderKey = keyGenerator.orderKey(orderId, skuId);
        return redisRepo.release(stockKey, buyersKey, orderKey, qty);
    }

    public record FlashSaleResult(boolean success, boolean duplicate, boolean insufficient, Instant expireAt) {}
}

