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
    private final DefaultRedisScript<Long> script;

    public FlashSaleRedisRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("lua/flash_sale.lua"));
        this.script.setResultType(Long.class);
    }

    /**
     * @return 1 success, 0 insufficient, -1 duplicate
     */
    public long execute(String stockKey,
                        String buyersKey,
                        String orderKey,
                        String streamKey,
                        String userId,
                        long qty,
                        Duration ttl,
                        String eventId,
                        String orderId,
                        String skuId,
                        String occurredAt) {
        Long res = redisTemplate.execute(
                script,
                List.of(stockKey, buyersKey, orderKey, streamKey),
                userId,
                Long.toString(qty),
                Long.toString(ttl.toSeconds()),
                eventId,
                orderId,
                skuId,
                occurredAt
        );
        return res == null ? -99 : res;
    }
}

