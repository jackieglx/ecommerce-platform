package com.lingxiao.search.messaging;

import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.SkuUpsertedEvent;
import com.lingxiao.search.service.IndexService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SkuUpsertedListener {

    private final IndexService indexService;

    public SkuUpsertedListener(IndexService indexService) {
        this.indexService = indexService;
    }

    @KafkaListener(topics = Topics.SKU_UPSERTED, containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(SkuUpsertedEvent event) {
        // 单条也走批量接口，后续可拓展批量消费
        indexService.indexSkus(List.of(event.skuId()));
    }
}

