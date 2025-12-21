package com.lingxiao.search.dto;

import java.time.Instant;

public record CatalogSkuResponse(
        String skuId,
        String productId,
        String title,
        String status,
        String brand,
        long priceCents,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {
}

