package com.lingxiao.contracts.events;

import java.time.Instant;

public record PaymentSucceededEvent(
        String eventId,
        String paymentId,
        String orderId,
        long amountCents,
        String currency,
        Instant paidAt
) {
}

