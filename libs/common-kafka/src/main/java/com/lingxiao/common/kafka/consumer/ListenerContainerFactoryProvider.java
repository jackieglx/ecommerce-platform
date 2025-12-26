package com.lingxiao.common.kafka.consumer;

import com.lingxiao.common.kafka.config.CommonKafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.converter.RecordMessageConverter;

public class ListenerContainerFactoryProvider {

    private final CommonKafkaProperties properties;
    private final CommonErrorHandlerFactory errorHandlerFactory;

    public ListenerContainerFactoryProvider(CommonKafkaProperties properties,
                                            CommonErrorHandlerFactory errorHandlerFactory) {
        this.properties = properties;
        this.errorHandlerFactory = errorHandlerFactory;
    }

    public ConcurrentKafkaListenerContainerFactory<Object, Object> build(ConsumerFactory<Object, Object> consumerFactory,
                                                                         KafkaTemplate<Object, Object> kafkaTemplate,
                                                                         RecordMessageConverter converter) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.valueOf(properties.getConsumer().getAckMode()));
        factory.getContainerProperties().setPollTimeout(properties.getConsumer().getPollTimeoutMs());
        factory.getContainerProperties().setMissingTopicsFatal(properties.getConsumer().getMissingTopicsFatal());
        if (properties.getConsumer().getConcurrency() != null) {
            factory.setConcurrency(properties.getConsumer().getConcurrency());
        }
        factory.setCommonErrorHandler(errorHandlerFactory.build(kafkaTemplate));
        if (converter != null) {
            factory.setRecordMessageConverter(converter);
        }
        return factory;
    }
}


