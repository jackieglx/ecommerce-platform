package com.lingxiao.common.redis;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

public class IdempotencyStore {

    private final StringRedisTemplate redisTemplate;

    public IdempotencyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Try to acquire an idempotency key. Returns true if acquired, false if already exists.
     */
    public boolean tryAcquire(String key, String value, Duration ttl) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Release the idempotency key (used on failure to allow retry).
     */
    public void release(String key) {
        redisTemplate.delete(key);
    }
}

