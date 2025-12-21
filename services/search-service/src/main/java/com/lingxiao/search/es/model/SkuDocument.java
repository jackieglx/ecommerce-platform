package com.lingxiao.search.es.model;

import java.time.Instant;

public record SkuDocument(
        String skuId,
        String productId,
        String title,
        String status,
        String brand,
        long priceCents,
        String currency,
        long sales7d,
        Instant createdAt,
        Instant updatedAt
) {
}

