package com.lingxiao.payment.domain;

import java.time.Instant;

public record Payment(
        String paymentId,
        String orderId,
        String status,
        long amountCents,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {
}

