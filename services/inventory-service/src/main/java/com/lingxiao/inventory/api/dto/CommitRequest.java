package com.lingxiao.inventory.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CommitRequest(
        @NotBlank String orderId
) {
}

