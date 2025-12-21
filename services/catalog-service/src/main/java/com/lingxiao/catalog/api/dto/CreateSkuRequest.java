package com.lingxiao.catalog.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateSkuRequest(
        @NotBlank String skuId,
        @NotBlank String productId,
        @NotBlank String title,
        @NotBlank String status,
        @NotBlank String brand,
        @Min(0) long priceCents,
        @NotBlank String currency
) {
}

