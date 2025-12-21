package com.lingxiao.catalog.application;

import com.lingxiao.catalog.api.dto.CreateSkuRequest;
import com.lingxiao.catalog.api.dto.SkuResponse;
import com.lingxiao.catalog.domain.model.Sku;
import com.lingxiao.catalog.infrastructure.db.SkuRepository;
import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.SkuUpsertedEvent;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class SkuAppService {

    private static final int MAX_BATCH = 200;
    private static final Logger log = LoggerFactory.getLogger(SkuAppService.class);

    private final SkuRepository repository;
    private final KafkaTemplate<String, SkuUpsertedEvent> kafkaTemplate;

    public SkuAppService(SkuRepository repository,
                         KafkaTemplate<String, SkuUpsertedEvent> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public SkuResponse create(CreateSkuRequest request) {
        Sku sku = repository.create(request);
        publishUpsertEvent(sku.skuId());
        return toResponse(sku);
    }

    public SkuResponse get(String skuId) {
        return toResponse(repository.get(skuId));
    }

    public List<SkuResponse> batchGet(List<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        List<String> orderedDistinct = new ArrayList<>(new LinkedHashSet<>(skuIds));
        if (orderedDistinct.size() > MAX_BATCH) {
            throw new IllegalArgumentException("too many ids, max " + MAX_BATCH);
        }

        List<Sku> found = repository.batchGet(orderedDistinct);
        Map<String, Sku> byId = found.stream().collect(Collectors.toMap(Sku::skuId, s -> s));

        List<SkuResponse> result = new ArrayList<>();
        for (String id : orderedDistinct) {
            Sku s = byId.get(id);
            if (s != null) {
                result.add(toResponse(s));
            }
        }
        return result;
    }

    private SkuResponse toResponse(Sku sku) {
        return new SkuResponse(
                sku.skuId(),
                sku.productId(),
                sku.title(),
                sku.status(),
                sku.brand(),
                sku.priceCents(),
                sku.currency(),
                sku.createdAt(),
                sku.updatedAt()
        );
    }

    private void publishUpsertEvent(String skuId) {
        SkuUpsertedEvent event = new SkuUpsertedEvent(
                UUID.randomUUID().toString(),
                skuId,
                Instant.now()
        );
        try {

            CompletableFuture<SendResult<String, SkuUpsertedEvent>> future =
                    kafkaTemplate.send(Topics.SKU_UPSERTED, skuId, event);

            future.whenComplete((res, ex) -> {
                if (ex != null) {
                    log.warn("Failed to publish SkuUpsertedEvent topic={} skuId={} eventId={}",
                            Topics.SKU_UPSERTED, skuId, event.eventId(), ex);
                } else if (log.isDebugEnabled()) {
                    log.debug("Published SkuUpsertedEvent topic={} partition={} offset={} skuId={} eventId={}",
                            res.getRecordMetadata().topic(),
                            res.getRecordMetadata().partition(),
                            res.getRecordMetadata().offset(),
                            skuId,
                            event.eventId());
                }
            });
        } catch (Exception e) {
            log.warn("Failed to publish SkuUpsertedEvent (sync exception) topic={} skuId={} eventId={}",
                    Topics.SKU_UPSERTED, skuId, event.eventId(), e);
        }
    }
}

