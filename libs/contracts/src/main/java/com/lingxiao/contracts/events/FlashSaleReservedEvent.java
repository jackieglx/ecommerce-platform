package com.lingxiao.contracts.events;

import java.time.Instant;

public record FlashSaleReservedEvent(
        String eventId,
        String orderId,
        String skuId,
        long qty,
        Instant occurredAt
) {
}

