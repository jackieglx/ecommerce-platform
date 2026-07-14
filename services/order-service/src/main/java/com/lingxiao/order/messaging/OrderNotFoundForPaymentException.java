package com.lingxiao.order.messaging;

/**
 * Signals a payment event arrived before the order was created/persisted.
 * Used to avoid blocking Kafka partitions: we can ACK the payment message and
 * reconcile asynchronously later.
 */
public class OrderNotFoundForPaymentException extends RuntimeException {

    private final String orderId;

    public OrderNotFoundForPaymentException(String orderId) {
        super("Order not found yet for payment, orderId=" + orderId);
        this.orderId = orderId;
    }

    public String orderId() {
        return orderId;
    }
}

