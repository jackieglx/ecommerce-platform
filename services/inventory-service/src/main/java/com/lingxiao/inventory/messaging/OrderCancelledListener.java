package com.lingxiao.inventory.messaging;

import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.OrderCancelledEvent;
import com.lingxiao.inventory.api.dto.ReserveItem;
import com.lingxiao.inventory.application.FlashSaleAppService;
import com.lingxiao.inventory.infrastructure.db.spanner.InventoryRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderCancelledListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelledListener.class);

    private final InventoryRepository repository;
    private final FlashSaleAppService flashSaleAppService;

    public OrderCancelledListener(InventoryRepository repository, FlashSaleAppService flashSaleAppService) {
        this.repository = repository;
        this.flashSaleAppService = flashSaleAppService;
    }

    @KafkaListener(topics = Topics.ORDER_CANCELLED)
    public void onCancelled(OrderCancelledEvent event, ConsumerRecord<String, OrderCancelledEvent> record) {
        String orderId = event.orderId();
        log.info("Handling ORDER_CANCELLED orderId={} partition={} offset={}", orderId, record.partition(), record.offset());

        // DB release first (idempotent)
        repository.release(orderId);

        List<ReserveItem> items = repository.getReservationItems(orderId);
        if (items.isEmpty()) {
            log.warn("ORDER_CANCELLED but no reservation items found, orderId={}", orderId);
            throw new IllegalStateException("No reservation items for orderId=" + orderId);
        }

        for (ReserveItem item : items) {
            long removed = flashSaleAppService.releaseRedisReservation(orderId, item.skuId(), item.qty());
            if (removed == 0) {
                log.warn("Redis release skipped (orderKey missing or buyer not present), orderId={}, skuId={}, qty={}", orderId, item.skuId(), item.qty());
            }
        }
    }
}


