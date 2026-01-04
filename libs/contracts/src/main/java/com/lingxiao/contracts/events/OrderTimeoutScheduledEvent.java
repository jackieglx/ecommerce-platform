package com.lingxiao.contracts.events;

import java.time.Instant;

public record OrderTimeoutScheduledEvent(
        String orderId,
        Instant expireAt
) {
}


