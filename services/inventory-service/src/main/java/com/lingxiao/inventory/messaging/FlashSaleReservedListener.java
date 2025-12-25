package com.lingxiao.inventory.messaging;

import com.lingxiao.common.idempotency.DoneAction;
import com.lingxiao.common.idempotency.Idempotent;
import com.lingxiao.common.idempotency.ProcessingAction;
import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.FlashSaleReservedEventV2;
import com.lingxiao.inventory.metrics.FlashSaleMetrics;
import com.lingxiao.inventory.infrastructure.db.spanner.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FlashSaleReservedListener {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleReservedListener.class);

    private final InventoryRepository repository;
    private final FlashSaleMetrics metrics;

    public FlashSaleReservedListener(InventoryRepository repository,
                                     FlashSaleMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @KafkaListener(topics = Topics.FLASH_SALE_RESERVED_V2)
    @Idempotent(
            eventType = "flashsale_reserved_v2",
            id = "#event.eventId()",
            onProcessing = ProcessingAction.RETRY,
            onDone = DoneAction.ACK,
            processingTtl = "PT30S",
            doneTtl = "PT2H"
    )
    public void onMessage(FlashSaleReservedEventV2 event) {
        try {
            repository.reserveBatch(event.orderId(),
                    List.of(new com.lingxiao.inventory.api.dto.ReserveItem(event.skuId(), event.qty())),
                    java.time.Duration.ofMinutes(15));
            metrics.incConsumerProcessed();
        } catch (Exception e) {
            log.warn("FlashSaleReservedListener failed orderId={} skuId={} eventId={}", event.orderId(), event.skuId(), event.eventId(), e);
            throw e;
        }
    }
}

