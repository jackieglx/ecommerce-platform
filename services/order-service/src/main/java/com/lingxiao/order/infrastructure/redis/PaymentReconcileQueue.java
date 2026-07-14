package com.lingxiao.order.infrastructure.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class PaymentReconcileQueue {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List<String>> claimScript;
    private final DefaultRedisScript<Long> ackScript;
    private final DefaultRedisScript<List<String>> reclaimScript;
    private final DefaultRedisScript<Long> rescheduleScript;

    private final String readyKey = "order:payrecon:ready";
    private final String processingKey = "order:payrecon:processing";
    private final String ownersKey = "order:payrecon:owners";
    private final String attemptsKey = "order:payrecon:attempts";

    public PaymentReconcileQueue(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.claimScript = loadListScript("lua/order_claim.lua");
        this.ackScript = loadLongScript("lua/order_ack.lua");
        this.reclaimScript = loadListScript("lua/order_reclaim.lua");
        this.rescheduleScript = loadLongScript("lua/order_reschedule.lua");
    }

    public void schedule(String orderId, Instant at) {
        redisTemplate.opsForZSet().add(readyKey, orderId, at.toEpochMilli());
    }

    public ClaimResult claimDue(int limit) {
        String token = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();
        List<String> res = redisTemplate.execute(claimScript,
                List.of(readyKey, processingKey, ownersKey),
                String.valueOf(now),
                String.valueOf(limit),
                token);
        if (res == null || res.isEmpty()) {
            return new ClaimResult(token, List.of());
        }
        return new ClaimResult(token, res);
    }

    public boolean ack(String orderId, String token) {
        return ack(orderId, token, true);
    }

    public boolean ackKeepAttempts(String orderId, String token) {
        return ack(orderId, token, false);
    }

    private boolean ack(String orderId, String token, boolean clearAttempts) {
        Long res = redisTemplate.execute(ackScript,
                List.of(processingKey, ownersKey),
                orderId, token);
        boolean ok = Long.valueOf(1L).equals(res);
        if (ok && clearAttempts) {
            clearAttempts(orderId);
        }
        return ok;
    }

    public List<String> reclaim(long staleMs, int limit) {
        long now = Instant.now().toEpochMilli();
        List<String> res = redisTemplate.execute(reclaimScript,
                List.of(readyKey, processingKey, ownersKey),
                String.valueOf(now),
                String.valueOf(staleMs),
                String.valueOf(limit));
        return res == null ? List.of() : res;
    }

    /**
     * Atomically move a task from processing -> ready with a new score.
     * Returns false if token mismatch (task is not owned by the caller).
     */
    public boolean reschedule(String orderId, String token, Instant nextAt) {
        long score = nextAt.toEpochMilli();
        Long res = redisTemplate.execute(rescheduleScript,
                List.of(processingKey, ownersKey, readyKey),
                orderId, token, Long.toString(score));
        return Long.valueOf(1L).equals(res);
    }

    public long incrementAttempt(String orderId) {
        return redisTemplate.opsForHash().increment(attemptsKey, orderId, 1L);
    }

    public void clearAttempts(String orderId) {
        redisTemplate.opsForHash().delete(attemptsKey, orderId);
    }

    @SuppressWarnings("unchecked")
    private DefaultRedisScript<List<String>> loadListScript(String path) {
        DefaultRedisScript<List<String>> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType((Class<List<String>>)(Class<?>) List.class);
        return script;
    }

    private DefaultRedisScript<Long> loadLongScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    public record ClaimResult(String token, List<String> orderIds) {}
}

