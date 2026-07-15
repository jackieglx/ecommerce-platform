package com.lingxiao.order.api;

import com.lingxiao.order.application.OrderSummary;

import java.time.Instant;

public record OrderResponse(
        String orderId,
        String skuId,
        String userId,
        long quantity,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    static OrderResponse from(OrderSummary summary) {
        return new OrderResponse(
                summary.orderId(), summary.skuId(), summary.userId(), summary.quantity(),
                summary.status(), summary.createdAt(), summary.updatedAt());
    }
}
