package com.lingxiao.inventory.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record FlashSaleReservationRequest(
        @NotBlank String skuId,
        @Min(1) Long qty
) {
    public long qtyOrDefault() {
        return qty == null ? 1L : qty;
    }
}


