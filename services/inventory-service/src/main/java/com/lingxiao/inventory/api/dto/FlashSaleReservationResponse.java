package com.lingxiao.inventory.api.dto;

import java.time.Instant;

public record FlashSaleReservationResponse(
        String status,
        String orderId,
        Instant reservationExpiresAt
) {
}


