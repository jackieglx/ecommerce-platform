package com.lingxiao.contracts.events;

import java.time.Instant;

public record SkuUpsertedEvent(
        String eventId,
        String skuId,
        Instant occurredAt
) {
}

