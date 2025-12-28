package com.lingxiao.catalog.application.command;

import com.lingxiao.catalog.api.dto.CreateSkuRequest;
import com.lingxiao.catalog.application.mapper.SkuMapper;
import com.lingxiao.catalog.api.dto.SkuResponse;
import com.lingxiao.catalog.api.dto.UpdateSkuRequest;
import com.lingxiao.catalog.domain.model.Sku;
import com.lingxiao.catalog.infrastructure.cache.SkuCache;
import com.lingxiao.catalog.infrastructure.cache.pubsub.CacheInvalidationPublisher;
import com.lingxiao.catalog.infrastructure.db.SkuRepository;
import com.lingxiao.contracts.Topics;
import com.lingxiao.contracts.events.SkuUpsertedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class SkuCommandService {
    private static final Logger log = LoggerFactory.getLogger(SkuCommandService.class);

    private final SkuRepository repository;
    private final KafkaTemplate<String, SkuUpsertedEvent> kafkaTemplate;
    private final CacheInvalidationPublisher invalidationPublisher;
    private final SkuCache cache;
    private final SkuMapper mapper;

    public SkuCommandService(SkuRepository repository,
                             KafkaTemplate<String, SkuUpsertedEvent> kafkaTemplate,
                             CacheInvalidationPublisher invalidationPublisher,
                             SkuCache cache,
                             SkuMapper mapper) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.invalidationPublisher = invalidationPublisher;
        this.cache = cache;
        this.mapper = mapper;
    }

    @Transactional
    public SkuResponse create(CreateSkuRequest request) {
        Sku sku = repository.create(request);
        afterCommit(() -> {
            invalidate(sku.skuId());
            publishUpsertEvent(sku.skuId());
        });
        return mapper.toResponse(sku);
    }

    @Transactional
    public SkuResponse update(String skuId, UpdateSkuRequest request) {
        Sku sku = repository.update(skuId, request);
        afterCommit(() -> {
            invalidate(skuId);
            publishUpsertEvent(skuId);
        });
        return mapper.toResponse(sku);
    }

    public void invalidate(String skuId) {
        try {
            cache.invalidateL2(skuId);
        } catch (Exception e) {
            log.warn("Invalidate L2 failed skuId={}", skuId, e);
        } finally {
            cache.invalidateL1(skuId);
        }
        try {
            invalidationPublisher.publish(skuId);
        } catch (Exception e) {
            log.warn("Publish cache invalidation failed skuId={}", skuId, e);
        }
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

    private void afterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}


