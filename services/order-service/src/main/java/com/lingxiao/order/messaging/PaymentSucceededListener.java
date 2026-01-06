package com.lingxiao.order.messaging;

import com.lingxiao.common.idempotency.DoneAction;
import com.lingxiao.common.idempotency.Idempotent;
import com.lingxiao.common.idempotency.ProcessingAction;
import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.PaymentSucceededEvent;
import com.lingxiao.order.infrastructure.db.spanner.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentSucceededListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentSucceededListener.class);

    private final OrderRepository repository;

    public PaymentSucceededListener(OrderRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = Topics.PAYMENT_SUCCEEDED, containerFactory = "kafkaListenerContainerFactory")
    @Idempotent(
            eventType = "payment_succeeded_v1",
            id = "#p0.eventId()",
            onProcessing = ProcessingAction.RETRY,
            onDone = DoneAction.ACK,
            processingTtl = "PT30S",
            doneTtl = "PT2H"
    )
    @Transactional
    public void onMessage(PaymentSucceededEvent event) {
        log.info("Received PaymentSucceededEvent paymentId={} orderId={}", event.paymentId(), event.orderId());

        // Update order status to PAID
        boolean updated = repository.markPaid(event.orderId(), event.paidAt());
        if (!updated) {
            log.warn("Failed to mark order as PAID orderId={} paymentId={}", event.orderId(), event.paymentId());
            // If order is already PAID or in another state, we still want to publish OrderPaidEvent
            // for idempotency (in case the previous attempt failed after marking PAID but before publishing)
        }

        // Publish OrderPaidEvent to outbox (within same transaction)
        repository.publishOrderPaidEvent(event.orderId(), event.paidAt());
        log.debug("Published OrderPaidEvent to outbox orderId={} paidAt={}", event.orderId(), event.paidAt());
    }
}

