package com.lingxiao.search.messaging;

import com.lingxiao.common.redis.IdempotencyStore;
import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.SkuUpsertedEvent;
import com.lingxiao.search.service.IndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class SkuUpsertedListener {

    private static final Logger log = LoggerFactory.getLogger(SkuUpsertedListener.class);

    private final IndexService indexService;
    private final IdempotencyStore idempotencyStore;
    private final Duration ttl;

    public SkuUpsertedListener(IndexService indexService,
                               IdempotencyStore idempotencyStore,
                               @Value("${search.idempotency.ttl:PT30M}") Duration ttl) {
        this.indexService = indexService;
        this.idempotencyStore = idempotencyStore;
        this.ttl = ttl;
    }

    @KafkaListener(topics = Topics.SKU_UPSERTED, containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(SkuUpsertedEvent event) {
        String eventId = event.eventId();
        if (eventId == null || eventId.isBlank()) {
            log.warn("Received event without eventId, processing without idempotency. skuId={}", event.skuId());
            indexService.indexSkus(List.of(event.skuId()));
            return;
        }
        String key = "idem:v1:search:index:event:" + eventId;
        boolean acquired = idempotencyStore.tryAcquire(key, event.skuId(), ttl);
        if (!acquired) {
            log.info("Skip duplicate eventId={} skuId={}", eventId, event.skuId());
            return;
        }
        try {
            indexService.indexSkus(List.of(event.skuId()));
        } catch (Exception e) {
            // allow retry
            idempotencyStore.release(key);
            throw e;
        }
    }
}

