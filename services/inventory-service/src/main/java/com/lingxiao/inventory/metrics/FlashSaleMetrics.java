package com.lingxiao.inventory.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class FlashSaleMetrics {

    private final Counter publisherSuccess;
    private final Counter publisherFail;
    private final Counter publisherRetry;
    private final Counter consumerProcessed;
    private final Counter consumerDuplicate;

    private final AtomicLong pendingGauge = new AtomicLong(0);
    private final AtomicLong lengthGauge = new AtomicLong(0);

    private final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final String group;

    public FlashSaleMetrics(MeterRegistry registry,
                            StringRedisTemplate redisTemplate,
                            @Value("${inventory.flashsale.outbox.stream-key:fs:outbox:flashsale-reserved}") String streamKey,
                            @Value("${inventory.flashsale.outbox.group:fs-publisher}") String group) {
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.group = group;

        this.publisherSuccess = Counter.builder("flashsale.publisher.kafka.success").register(registry);
        this.publisherFail = Counter.builder("flashsale.publisher.kafka.fail").register(registry);
        this.publisherRetry = Counter.builder("flashsale.publisher.retry").register(registry);
        this.consumerProcessed = Counter.builder("flashsale.consumer.processed").register(registry);
        this.consumerDuplicate = Counter.builder("flashsale.consumer.duplicate").register(registry);

        Gauge.builder("flashsale.redis.stream.pending", pendingGauge, AtomicLong::get).register(registry);
        Gauge.builder("flashsale.redis.stream.length", lengthGauge, AtomicLong::get).register(registry);
    }

    public void incPublisherSuccess() { publisherSuccess.increment(); }
    public void incPublisherFail() { publisherFail.increment(); }
    public void incPublisherRetry() { publisherRetry.increment(); }
    public void incConsumerProcessed() { consumerProcessed.increment(); }
    public void incConsumerDuplicate() { consumerDuplicate.increment(); }

    @Scheduled(fixedDelayString = "5000")
    public void refreshGauges() {
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream().pending(streamKey, group);
            if (summary != null) {
                pendingGauge.set(summary.getTotalPendingMessages());
            }
        } catch (Exception ignored) {
            // keep old value
        }
        try {
            Long len = redisTemplate.opsForStream().size(streamKey);
            if (len != null) {
                lengthGauge.set(len);
            }
        } catch (Exception ignored) {
            // keep old value
        }
    }
}

