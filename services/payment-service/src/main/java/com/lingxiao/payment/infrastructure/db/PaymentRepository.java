package com.lingxiao.payment.infrastructure.db;

import com.lingxiao.payment.domain.Payment;

import java.util.Optional;

public interface PaymentRepository {
    Payment create(String paymentId, String orderId, String status, long amountCents, String currency);

    Optional<Payment> getByPaymentId(String paymentId);

    Optional<Payment> getByOrderId(String orderId);
}

