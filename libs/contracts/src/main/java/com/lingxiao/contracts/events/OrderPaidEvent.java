package com.lingxiao.contracts.events;

import java.time.Instant;
import java.util.List;

public record OrderPaidEvent(
        String eventId,
        String orderId,
        Instant paidAt,
        List<OrderLineItem> items
) {
}

