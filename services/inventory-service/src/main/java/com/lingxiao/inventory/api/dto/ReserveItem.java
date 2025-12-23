package com.lingxiao.inventory.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ReserveItem(
        @NotBlank String skuId,
        @Min(1) long qty
) {
}

