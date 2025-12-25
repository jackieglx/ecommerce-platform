package com.lingxiao.common.idempotency.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;

public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<String> acquireScript;
    private final DefaultRedisScript<Long> markDoneScript;
    private final DefaultRedisScript<Long> releaseScript;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.acquireScript = loadStringScript("idempotency/acquire.lua");
        this.markDoneScript = loadLongScript("idempotency/mark_done.lua");
        this.releaseScript = loadLongScript("idempotency/release.lua");
    }

    @Override
    public AcquireResult acquire(String key, String token, Duration processingTtl) {
        long ttlSeconds = Math.max(1, processingTtl.getSeconds());
        String res = redisTemplate.execute(acquireScript, Collections.singletonList(key),
                Long.toString(ttlSeconds), token);
        if (res == null) {
            return AcquireResult.PROCESSING;
        }
        return switch (res) {
            case "ACQUIRED" -> AcquireResult.ACQUIRED;
            case "DONE" -> AcquireResult.DONE;
            default -> AcquireResult.PROCESSING;
        };
    }

    @Override
    public boolean markDone(String key, String token, Duration doneTtl) {
        long ttlSeconds = Math.max(1, doneTtl.getSeconds());
        Long res = redisTemplate.execute(markDoneScript, Collections.singletonList(key),
                token, Long.toString(ttlSeconds));
        return Objects.equals(res, 1L);
    }

    @Override
    public void release(String key, String token) {
        try {
            redisTemplate.execute(releaseScript, Collections.singletonList(key), token);
        } catch (Exception e) {
            log.debug("Release idempotency key failed key={}", key, e);
        }
    }

    private DefaultRedisScript<String> loadStringScript(String path) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(String.class);
        return script;
    }

    private DefaultRedisScript<Long> loadLongScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}

