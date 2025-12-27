package com.lingxiao.common.kafka.consumer;

import com.lingxiao.common.idempotency.IdempotencyCompletedException;
import com.lingxiao.common.idempotency.IdempotencyPayloadMismatchException;
import com.lingxiao.common.kafka.config.CommonKafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.function.BiFunction;

public class CommonErrorHandlerFactory {

    private final CommonKafkaProperties properties;
    private final ExceptionClassifier classifier;

    public CommonErrorHandlerFactory(CommonKafkaProperties properties, ExceptionClassifier classifier) {
        this.properties = properties;
        this.classifier = classifier;
    }

    public DefaultErrorHandler build(@Nullable KafkaTemplate<?, ?> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = (properties.getDlt().isEnabled() && kafkaTemplate != null)
                ? buildRecoverer(kafkaTemplate)
                : null;
        DefaultErrorHandler handler = recoverer == null
                ? new DefaultErrorHandler(mainBackOff())
                : new DefaultErrorHandler(recoverer, mainBackOff());
        handler.setBackOffFunction(backOffFunction());
        handler.setRetryListeners((record, ex, deliveryAttempt) -> {
            // hook for metrics/logging later
        });
        handler.addNotRetryableExceptions(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                org.apache.kafka.common.errors.SerializationException.class,
                org.springframework.messaging.converter.MessageConversionException.class,
                org.springframework.validation.BindException.class,
                IllegalArgumentException.class,
                IdempotencyPayloadMismatchException.class,
                IdempotencyCompletedException.class
        );
        return handler;
    }

    private DeadLetterPublishingRecoverer buildRecoverer(KafkaTemplate<?, ?> kafkaTemplate) {
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> resolver = (rec, ex) -> {
            String topic = rec.topic() + properties.getDlt().getSuffix();
            int partition = properties.getDlt().isSamePartition() ? rec.partition() : -1;
            return new TopicPartition(topic, partition);
        };
        return new DeadLetterPublishingRecoverer(kafkaTemplate, resolver);
    }

    private BackOff mainBackOff() {
        CommonKafkaProperties.Retry r = properties.getRetry();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(r.getMaxAttempts());
        backOff.setInitialInterval(r.getInitialBackoffMs());
        backOff.setMultiplier(r.getMultiplier());
        backOff.setMaxInterval(r.getMaxBackoffMs());
        return backOff;
    }

    private BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> backOffFunction() {
        CommonKafkaProperties.Retry r = properties.getRetry();
        ExponentialBackOff inProgress = new ExponentialBackOff(r.getInProgressInitialBackoffMs(), r.getMultiplier());
        inProgress.setMaxInterval(r.getInProgressMaxBackoffMs());
        inProgress.setMaxElapsedTime(Duration.ofMillis(r.getInProgressMaxBackoffMs() * r.getMaxAttempts()).toMillis());
        return (rec, ex) -> {
            if (ex != null && classifier.isInProgress(ex)) {
                return inProgress;
            }
            if (ex != null && classifier.isMarkDoneFailed(ex)) {
                return mainBackOff();
            }
            return null; // fall back to default backoff configured on handler
        };
    }
}


