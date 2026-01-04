package com.lingxiao.inventory.messaging;

import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.InventoryReleaseRequestedEvent;
import com.lingxiao.inventory.application.FlashSaleAppService;
import com.lingxiao.inventory.infrastructure.db.spanner.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryReleaseRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryReleaseRequestedListener.class);

    private final InventoryRepository repository;
    private final FlashSaleAppService flashSaleAppService;

    public InventoryReleaseRequestedListener(InventoryRepository repository,
                                             FlashSaleAppService flashSaleAppService) {
        this.repository = repository;
        this.flashSaleAppService = flashSaleAppService;
    }

    @KafkaListener(topics = Topics.INVENTORY_RELEASE_REQUESTED)
    public void onMessage(InventoryReleaseRequestedEvent event) {
        log.info("Received InventoryReleaseRequestedEvent orderId={} reason={}", event.orderId(), event.reason());

        // 1) 先释放 DB 预占（幂等）
        repository.release(event.orderId());

        if (event.items() == null || event.items().isEmpty()) {
            log.warn("InventoryReleaseRequestedEvent has no items orderId={}, triggering retry", event.orderId());
            throw new RuntimeException("No items to release for orderId=" + event.orderId());
        }

        // 2) 回补 Redis（幂等：orderKey 不存在就不会重复加）
        event.items().forEach(it -> {
            long removed = flashSaleAppService.releaseRedisReservation(event.orderId(), it.skuId(), it.qty());
            if (removed == 0) {
                log.warn("Redis release returned 0 orderId={} skuId={} qty={}", event.orderId(), it.skuId(), it.qty());
            }
        });

        log.info("Inventory release completed for orderId={}", event.orderId());
    }
}

