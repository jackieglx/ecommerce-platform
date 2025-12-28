package com.lingxiao.catalog.api.dto;

import jakarta.validation.constraints.Min;

public record UpdateSkuRequest(
        String title,
        String status,
        String brand,
        @Min(0) Long priceCents,
        String currency
) {
}


