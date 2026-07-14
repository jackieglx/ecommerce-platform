package com.lingxiao.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.PaymentSucceededEvent;
import com.lingxiao.payment.domain.Payment;
import com.lingxiao.payment.infrastructure.db.PaymentRepository;
import com.lingxiao.payment.infrastructure.db.spanner.SpannerPaymentRepository;
import com.lingxiao.payment.infrastructure.redis.OrderSnapshotRepository;
import com.lingxiao.payment.infrastructure.redis.PaymentMarkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository repository;
    private final SpannerPaymentRepository spannerPaymentRepository;
    private final ObjectMapper objectMapper;
    private final OrderSnapshotRepository snapshotRepository;
    private final PaymentMarkerRepository markerRepository;

    public PaymentService(PaymentRepository repository,
                         SpannerPaymentRepository spannerPaymentRepository,
                         ObjectMapper objectMapper,
                         OrderSnapshotRepository snapshotRepository,
                         PaymentMarkerRepository markerRepository) {
        this.repository = repository;
        this.spannerPaymentRepository = spannerPaymentRepository;
        this.objectMapper = objectMapper;
        this.snapshotRepository = snapshotRepository;
        this.markerRepository = markerRepository;
    }

    public Payment succeedPayment(String orderId, String callerUserId) {
        String paymentId = UUID.randomUUID().toString();
        
        // Check if payment already exists (idempotency)
        Payment existingPayment = repository.getByOrderId(orderId).orElse(null);
        if (existingPayment != null) {
            // 幂等命中：不重复发事件
            log.debug("Idempotent payment hit, skip publish. orderId={} paymentId={}", orderId, existingPayment.paymentId());
            // Ensure marker exists for strong cancel/release safety.
            markerRepository.markPaid(orderId, existingPayment.paymentId(), existingPayment.createdAt(),
                    existingPayment.amountCents(), existingPayment.currency());
            return existingPayment;
        }

        OrderSnapshotRepository.OrderSnapshot snap = snapshotRepository.getRequired(orderId);
        if (callerUserId != null && !callerUserId.isBlank() && !callerUserId.equals(snap.userId())) {
            throw new IllegalArgumentException("orderId does not belong to caller");
        }
        if (snap.expireAt() != null && Instant.now().isAfter(snap.expireAt())) {
            throw new IllegalStateException("ORDER_EXPIRED");
        }
        long amountCents = Math.multiplyExact(snap.priceCents(), snap.qty());
        String currency = snap.currency();

        // Create payment event payload
        PaymentSucceededEvent event = new PaymentSucceededEvent(
                UUID.randomUUID().toString(),
                paymentId,
                orderId,
                amountCents,
                currency,
                Instant.now()
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

        // Strong guarantee: write payment marker to Redis so timeout cancel/release can gate on it
        // even if Kafka delivery is delayed.
        markerRepository.markPaid(orderId, payment.paymentId(), payment.createdAt(), payment.amountCents(), payment.currency());

        log.debug("Created payment with outbox event. orderId={} paymentId={} outboxId={}", 
                orderId, payment.paymentId(), outboxId);
        
        return payment;
    }

}

