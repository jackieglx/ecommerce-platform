package com.lingxiao.payment.infrastructure.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Repository
public class PaymentMarkerRepository {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> markScript;
    private final String markerPrefix;
    private final Duration markerTtl;

    public PaymentMarkerRepository(StringRedisTemplate redisTemplate,
                                   @Value("${payment.marker-prefix:fs:pay:}") String markerPrefix,
                                   @Value("${payment.marker-ttl:PT2H}") Duration markerTtl) {
        this.redisTemplate = redisTemplate;
        this.markScript = loadLongScript("lua/payment_marker.lua");
        this.markerPrefix = markerPrefix;
        this.markerTtl = markerTtl;
    }

    public void markPaid(String orderId, String paymentId, Instant paidAt, long amountCents, String currency) {
        if (!StringUtils.hasText(orderId) || !StringUtils.hasText(paymentId)) {
            throw new IllegalArgumentException("orderId/paymentId must not be blank");
        }
        OrderIdParts parts = OrderIdParts.parse(orderId);
        String key = markerPrefix + "{" + parts.activityId + ":" + parts.shardId + "}" + ":order:" + orderId;
        long ttlMs = markerTtl.toMillis();
        redisTemplate.execute(
                markScript,
                List.of(key),
                orderId,
                paymentId,
                paidAt == null ? "" : paidAt.toString(),
                Long.toString(amountCents),
                currency == null ? "" : currency,
                Long.toString(ttlMs)
        );
    }

    private static DefaultRedisScript<Long> loadLongScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
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
}

