package com.lingxiao.inventory.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReserveRequest(
        @NotBlank String orderId,
        @NotEmpty List<ReserveItem> items
) {
}

