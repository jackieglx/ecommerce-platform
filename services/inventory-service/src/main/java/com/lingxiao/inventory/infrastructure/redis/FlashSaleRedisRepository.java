package com.lingxiao.inventory.infrastructure.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

@Repository
public class FlashSaleRedisRepository {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> releaseScript;

    public FlashSaleRedisRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.reserveScript = new DefaultRedisScript<>();
        this.reserveScript.setLocation(new ClassPathResource("lua/flash_sale.lua"));
        this.reserveScript.setResultType(Long.class);

        this.releaseScript = new DefaultRedisScript<>();
        this.releaseScript.setLocation(new ClassPathResource("lua/flash_sale_release.lua"));
        this.releaseScript.setResultType(Long.class);
    }

    /**
     * @return 1 success, 0 insufficient, -1 duplicate, -3 price missing (not preheated)
     */
    public long execute(String stockKey,
                        String buyersKey,
                        String orderKey,
                        String streamKey,
                        String snapshotKey,
                        String priceKey,
                        String userId,
                        long qty,
                        Duration ttl,
                        Duration snapshotTtl,
                        Duration buyersTtl,
                        String eventId,
                        String orderId,
                        String skuId,
                        String occurredAt,
                        String expireAt) {
        Long res = redisTemplate.execute(
                reserveScript,
                List.of(stockKey, buyersKey, orderKey, streamKey, snapshotKey, priceKey),
                userId,
                Long.toString(qty),
                Long.toString(ttl.toSeconds()),
                Long.toString(Math.max(1, snapshotTtl.toSeconds())),
                Long.toString(Math.max(0, buyersTtl.toSeconds())),
                eventId,
                orderId,
                skuId,
                occurredAt,
                expireAt
        );
        return res == null ? -99 : res;
    }

    /**
     * @return removed count from buyers set (0 or 1)
     */
    public long release(String stockKey,
                        String buyersKey,
                        String orderKey,
                        long qty) {
        Long res = redisTemplate.execute(
                releaseScript,
                List.of(stockKey, buyersKey, orderKey),
                Long.toString(qty)
        );
        return res == null ? 0 : res;
    }
}

