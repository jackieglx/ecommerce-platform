package com.lingxiao.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.PaymentSucceededEvent;
import com.lingxiao.payment.domain.Payment;
import com.lingxiao.payment.infrastructure.db.PaymentRepository;
import com.lingxiao.payment.infrastructure.db.spanner.SpannerPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository repository;
    private final SpannerPaymentRepository spannerPaymentRepository;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository repository,
                         SpannerPaymentRepository spannerPaymentRepository,
                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.spannerPaymentRepository = spannerPaymentRepository;
        this.objectMapper = objectMapper;
    }

    public Payment succeedPayment(String orderId, long amountCents, String currency) {
        String paymentId = UUID.randomUUID().toString();
        
        // Check if payment already exists (idempotency)
        Payment existingPayment = repository.getByOrderId(orderId).orElse(null);
        if (existingPayment != null) {
            // 幂等命中：不重复发事件
            log.debug("Idempotent payment hit, skip publish. orderId={} paymentId={}", orderId, existingPayment.paymentId());
            return existingPayment;
        }

        // Create payment event payload
        PaymentSucceededEvent event = new PaymentSucceededEvent(
                UUID.randomUUID().toString(),
                paymentId,
                orderId,
                amountCents,
                currency,
                null // createdAt will be set by DB
        );

        // Serialize event to JSON
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize PaymentSucceededEvent", e);
        }

        // Create payment and outbox event in the same transaction
        String outboxId = UUID.randomUUID().toString();
        Payment payment = spannerPaymentRepository.createWithOutbox(
                paymentId, orderId, "SUCCEEDED", amountCents, currency,
                outboxId, "PAYMENT_SUCCEEDED", Topics.PAYMENT_SUCCEEDED, orderId, payloadJson
        );

        log.debug("Created payment with outbox event. orderId={} paymentId={} outboxId={}", 
                orderId, payment.paymentId(), outboxId);
        
        return payment;
    }

}

