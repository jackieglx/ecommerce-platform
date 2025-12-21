package com.lingxiao.search.api.dto;

import java.time.Instant;

public record SearchResponseItem(
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

