package com.lingxiao.inventory.application;

import java.time.Instant;
import java.util.Objects;

public record FlashSaleReservationResult(Status status,
                                         String orderId,
                                         Instant reservationExpiresAt,
                                         String skuId,
                                         long qty) {

    public enum Status {
        RESERVED,
        DUPLICATE,
        SOLD_OUT,
        FAILED
    }

    public String toPointer() {
        String expires = reservationExpiresAt == null ? "" : reservationExpiresAt.toString();
        String sku = skuId == null ? "" : skuId;
        return status.name() + "|" + (orderId == null ? "" : orderId) + "|" + expires + "|" + sku + "|" + qty;
    }

    public static FlashSaleReservationResult fromPointer(String pointer) {
        Objects.requireNonNull(pointer, "pointer must not be null");
        String[] parts = pointer.split("\\|", -1);
        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid pointer: " + pointer);
        }
        Status status = Status.valueOf(parts[0]);
        String orderId = parts[1].isEmpty() ? null : parts[1];
        Instant expiresAt = parts[2].isEmpty() ? null : Instant.parse(parts[2]);
        String sku = parts[3].isEmpty() ? null : parts[3];
        long qty = parts[4].isEmpty() ? 0L : Long.parseLong(parts[4]);
        return new FlashSaleReservationResult(status, orderId, expiresAt, sku, qty);
    }
}


