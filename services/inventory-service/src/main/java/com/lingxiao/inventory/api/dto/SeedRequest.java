package com.lingxiao.inventory.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SeedRequest(
        @NotBlank String skuId,
        @Min(0) long onHand
) {
}

