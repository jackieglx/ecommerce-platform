package com.lingxiao.payment.application;

import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.PaymentSucceededEvent;
import com.lingxiao.payment.domain.Payment;
import com.lingxiao.payment.infrastructure.db.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository repository;
    private final KafkaTemplate<String, PaymentSucceededEvent> kafkaTemplate;

    public PaymentService(PaymentRepository repository,
                         KafkaTemplate<String, PaymentSucceededEvent> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public Payment succeedPayment(String orderId, long amountCents, String currency) {
        // Check if payment already exists for this order (idempotency)
        var existing = repository.getByOrderId(orderId);
        if (existing.isPresent()) {
            log.debug("Payment already exists for orderId={} paymentId={}", orderId, existing.get().paymentId());
            return existing.get();
        }

        String paymentId = UUID.randomUUID().toString();
        Payment payment = repository.create(paymentId, orderId, "SUCCEEDED", amountCents, currency);

        afterCommit(() -> publishPaymentSucceededEvent(payment));

        return payment;
    }

    private void publishPaymentSucceededEvent(Payment payment) {
        PaymentSucceededEvent event = new PaymentSucceededEvent(
                UUID.randomUUID().toString(),
                payment.paymentId(),
                payment.orderId(),
                payment.amountCents(),
                payment.currency(),
                payment.createdAt()
        );
        try {
            CompletableFuture<SendResult<String, PaymentSucceededEvent>> future =
                    kafkaTemplate.send(Topics.PAYMENT_SUCCEEDED, payment.orderId(), event);
            future.whenComplete((res, ex) -> {
                if (ex != null) {
                    log.warn("Failed to publish PaymentSucceededEvent topic={} orderId={} eventId={}",
                            Topics.PAYMENT_SUCCEEDED, payment.orderId(), event.eventId(), ex);
                } else if (log.isDebugEnabled()) {
                    log.debug("Published PaymentSucceededEvent topic={} partition={} offset={} orderId={} eventId={}",
                            res.getRecordMetadata().topic(),
                            res.getRecordMetadata().partition(),
                            res.getRecordMetadata().offset(),
                            payment.orderId(),
                            event.eventId());
                }
            });
        } catch (Exception e) {
            log.warn("Failed to publish PaymentSucceededEvent (sync exception) topic={} orderId={} eventId={}",
                    Topics.PAYMENT_SUCCEEDED, payment.orderId(), event.eventId(), e);
        }
    }

    private void afterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}

