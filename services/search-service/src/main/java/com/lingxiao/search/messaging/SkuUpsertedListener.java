package com.lingxiao.search.messaging;

import com.lingxiao.common.idempotency.Idempotent;
import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.SkuUpsertedEvent;
import com.lingxiao.search.service.IndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SkuUpsertedListener {

    private static final Logger log = LoggerFactory.getLogger(SkuUpsertedListener.class);

    private final IndexService indexService;

    public SkuUpsertedListener(IndexService indexService) {
        this.indexService = indexService;
    }

    @KafkaListener(topics = Topics.SKU_UPSERTED, containerFactory = "kafkaListenerContainerFactory")
    @Idempotent(eventType = "sku_upserted_v1", id = "#event.eventId()")
    public void onMessage(SkuUpsertedEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            log.warn("Received event without eventId, processing without idempotency. skuId={}", event.skuId());
            indexService.indexSkus(List.of(event.skuId()));
            return;
        }
        indexService.indexSkus(List.of(event.skuId()));
    }
}

