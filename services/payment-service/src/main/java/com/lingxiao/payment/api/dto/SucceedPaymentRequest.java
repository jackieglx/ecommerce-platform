package com.lingxiao.payment.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SucceedPaymentRequest(
        @NotBlank String orderId,
        @NotNull @Min(1) Long amountCents,
        @NotBlank String currency
) {
}

