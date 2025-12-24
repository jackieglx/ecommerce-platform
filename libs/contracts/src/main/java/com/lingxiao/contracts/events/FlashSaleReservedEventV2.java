package com.lingxiao.contracts.events;

import java.time.Instant;

public record FlashSaleReservedEventV2(
        String eventId,
        String orderId,
        String userId,
        String skuId,
        long qty,
        long priceCents,
        String currency,
        Instant occurredAt,
        Instant expireAt
) {
}

