package com.lingxiao.order.application;

import java.time.Instant;

public record OrderSummary(
        String orderId,
        String skuId,
        String userId,
        long quantity,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
