package com.lingxiao.order.application;

import com.lingxiao.contracts.events.FlashSaleReservedEventV2;
import com.lingxiao.contracts.events.OrderTimeoutScheduledEvent;
import com.lingxiao.order.infrastructure.db.spanner.OrderRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OrderAppService {

    private static final Logger log = LoggerFactory.getLogger(OrderAppService.class);

    private final OrderRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderAppService(OrderRepository repository,
                           KafkaTemplate<String, Object> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void handleFlashSaleReservation(FlashSaleReservedEventV2 event) {
        repository.createFromFlashSaleEvent(event);
        if (event.expireAt() != null) {
            kafkaTemplate.send(com.lingxiao.contracts.Topics.ORDER_TIMEOUT_SCHEDULED,
                    event.orderId(),
                    new OrderTimeoutScheduledEvent(event.orderId(), event.expireAt()));
        }
        log.debug("Handled flash sale reservation eventId={} orderId={} userId={} skuId={}",
                event.eventId(), event.orderId(), event.userId(), event.skuId());
    }
}

