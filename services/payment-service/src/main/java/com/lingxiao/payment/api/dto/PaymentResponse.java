package com.lingxiao.payment.api.dto;

import java.time.Instant;

public record PaymentResponse(
        String paymentId,
        String orderId,
        String status,
        long amountCents,
        String currency,
        Instant createdAt
) {
}

