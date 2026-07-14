package com.lingxiao.payment.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SucceedPaymentRequest(
        @NotBlank String orderId
) {
}

