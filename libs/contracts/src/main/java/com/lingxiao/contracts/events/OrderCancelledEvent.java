package com.lingxiao.contracts.events;

import java.time.Instant;

public record OrderCancelledEvent(
        String orderId,
        String reason,
        Instant cancelledAt
) {
}


