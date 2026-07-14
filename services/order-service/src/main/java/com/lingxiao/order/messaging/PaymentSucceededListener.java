package com.lingxiao.order.messaging;

import com.lingxiao.common.idempotency.DoneAction;
import com.lingxiao.common.idempotency.Idempotent;
import com.lingxiao.common.idempotency.ProcessingAction;
import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.PaymentSucceededEvent;
import com.lingxiao.order.infrastructure.db.spanner.OrderRepository;
import com.lingxiao.order.infrastructure.redis.PaymentReconcileQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentSucceededListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentSucceededListener.class);

    private final OrderRepository repository;
    private final PaymentReconcileQueue reconcileQueue;

    public PaymentSucceededListener(OrderRepository repository,
                                   PaymentReconcileQueue reconcileQueue) {
        this.repository = repository;
        this.reconcileQueue = reconcileQueue;
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
    public void onMessage(PaymentSucceededEvent event) {
        log.info("Received PaymentSucceededEvent paymentId={} orderId={}", event.paymentId(), event.orderId());
        try {
            repository.handlePaymentSucceeded(event);
        } catch (OrderNotFoundForPaymentException e) {
            // Non-blocking retry: persist pending payment (already done in repository) and
            // enqueue a delayed reconcile attempt; ACK the Kafka message.
            reconcileQueue.schedule(e.orderId(), java.time.Instant.now().plusMillis(200));
            log.debug("Order not found yet, enqueued reconcile orderId={}", e.orderId());
        }
    }
}

