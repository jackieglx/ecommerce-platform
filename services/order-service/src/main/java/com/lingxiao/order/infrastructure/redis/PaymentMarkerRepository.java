package com.lingxiao.order.infrastructure.redis;

import com.lingxiao.contracts.events.PaymentSucceededEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

@Repository
public class PaymentMarkerRepository {

    private final StringRedisTemplate redisTemplate;
    private final String markerPrefix;

    public PaymentMarkerRepository(
            StringRedisTemplate redisTemplate,
            @Value("${order.timeout.payment-marker-prefix:fs:pay:}") String markerPrefix
    ) {
        this.redisTemplate = redisTemplate;
        this.markerPrefix = markerPrefix;
    }

    /**
     * Returns marker if payment-service has marked this order as paid.
     * Key format (written by payment-service): fs:pay:{activityId:shard}:order:{orderId}
     */
    public PaymentMarker get(String orderId) {
        if (!StringUtils.hasText(orderId)) return null;
        OrderIdParts parts = OrderIdParts.parse(orderId);
        String key = markerPrefix + "{" + parts.activityId + ":" + parts.shardId + "}" + ":order:" + orderId;

        List<Object> values = redisTemplate.opsForHash().multiGet(
                key,
                List.of("paymentId", "paidAt", "amountCents", "currency")
        );
        if (values == null || values.isEmpty()) return null;

        String paymentId = values.get(0) == null ? null : values.get(0).toString();
        if (!StringUtils.hasText(paymentId)) return null; // treat missing marker as not paid

        Instant paidAt = null;
        if (values.size() > 1 && values.get(1) != null) {
            String s = values.get(1).toString();
            if (StringUtils.hasText(s)) {
                paidAt = Instant.parse(s);
            }
        }

        long amountCents = 0L;
        if (values.size() > 2 && values.get(2) != null) {
            String s = values.get(2).toString();
            if (StringUtils.hasText(s)) {
                amountCents = Long.parseLong(s);
            }
        }

        String currency = null;
        if (values.size() > 3 && values.get(3) != null) {
            String s = values.get(3).toString();
            currency = StringUtils.hasText(s) ? s : null;
        }

        return new PaymentMarker(orderId, paymentId, amountCents, currency, paidAt);
    }

    public record PaymentMarker(String orderId, String paymentId, long amountCents, String currency, Instant paidAt) {
        public PaymentSucceededEvent toEvent(String eventId) {
            return new PaymentSucceededEvent(
                    eventId,
                    paymentId,
                    orderId,
                    amountCents,
                    currency,
                    paidAt
            );
        }
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

