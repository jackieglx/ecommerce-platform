package com.lingxiao.catalog.api.dto;

import java.time.Instant;

public record SkuResponse(
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

