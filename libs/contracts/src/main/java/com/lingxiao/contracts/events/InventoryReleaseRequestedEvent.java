package com.lingxiao.contracts.events;

import java.time.Instant;
import java.util.List;

public record InventoryReleaseRequestedEvent(
        String eventId,
        String orderId,
        String reason,
        List<Item> items,
        Instant occurredAt
) {
    public record Item(String skuId, long qty) {
    }
}

