package com.lingxiao.inventory.messaging;

import com.lingxiao.common.redis.IdempotencyStore;
import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.FlashSaleReservedEvent;
import com.lingxiao.inventory.metrics.FlashSaleMetrics;
import com.lingxiao.inventory.infrastructure.db.spanner.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Duration;

@Component
public class FlashSaleReservedListener {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleReservedListener.class);

    private final InventoryRepository repository;
    private final IdempotencyStore idempotencyStore;
    private final Duration idempotencyTtl;
    private final FlashSaleMetrics metrics;

    public FlashSaleReservedListener(InventoryRepository repository,
                                     IdempotencyStore idempotencyStore,
                                     FlashSaleMetrics metrics,
                                     @Value("${inventory.flashsale.idempotency-ttl:PT2H}") Duration idempotencyTtl) {
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
        this.idempotencyTtl = idempotencyTtl;
        this.metrics = metrics;
    }

    @KafkaListener(topics = Topics.FLASH_SALE_RESERVED)
    public void onMessage(FlashSaleReservedEvent event) {
        String key = "idem:v1:inventory:flashsale:" + event.eventId();
        try {
            boolean acquired = idempotencyStore.tryAcquire(key, event.orderId(), idempotencyTtl);
            if (!acquired) {
                log.debug("Skip duplicate flash sale eventId={} orderId={} skuId={}", event.eventId(), event.orderId(), event.skuId());
                metrics.incConsumerDuplicate();
                return;
            }
            repository.reserveBatch(event.orderId(),
                    List.of(new com.lingxiao.inventory.api.dto.ReserveItem(event.skuId(), event.qty())),
                    java.time.Duration.ofMinutes(15));
            metrics.incConsumerProcessed();
        } catch (Exception e) {
            log.warn("FlashSaleReservedListener failed orderId={} skuId={} eventId={}", event.orderId(), event.skuId(), event.eventId(), e);
            // allow retry
            idempotencyStore.release(key);
            throw e;
        }
    }
}

