package com.lingxiao.catalog.domain.model;

import java.time.Instant;

public record Sku(
        String skuId,
        String productId,
        String title,
        String status,
        long priceCents,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {
}

