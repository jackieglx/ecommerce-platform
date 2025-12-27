package com.lingxiao.common.kafka.autoconfigure;

import com.lingxiao.common.kafka.config.CommonKafkaProperties;
import com.lingxiao.common.kafka.consumer.CommonErrorHandlerFactory;
import com.lingxiao.common.kafka.consumer.ExceptionClassifier;
import com.lingxiao.common.kafka.consumer.ListenerContainerFactoryProvider;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.converter.RecordMessageConverter;

@AutoConfiguration
@ConditionalOnClass(ConcurrentKafkaListenerContainerFactory.class)
@EnableConfigurationProperties(CommonKafkaProperties.class)
public class KafkaConsumerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExceptionClassifier exceptionClassifier() {
        return new ExceptionClassifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public CommonErrorHandlerFactory commonErrorHandlerFactory(CommonKafkaProperties props,
                                                               ExceptionClassifier classifier) {
        return new CommonErrorHandlerFactory(props, classifier);
    }

    @Bean(name = "kafkaListenerContainerFactory")
    @ConditionalOnMissingBean(name = "kafkaListenerContainerFactory")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ConsumerFactory.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "spring.kafka.consumer", name = "group-id")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            ObjectProvider<KafkaTemplate<?, ?>> kafkaTemplateProvider,
            CommonKafkaProperties props,
            CommonErrorHandlerFactory errorHandlerFactory) {
        if (props.getConsumer().getMaxPollRecords() != null) {
            consumerFactory.updateConfigs(
                    java.util.Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, props.getConsumer().getMaxPollRecords()));
        }
        KafkaTemplate<?, ?> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        ListenerContainerFactoryProvider provider = new ListenerContainerFactoryProvider(props, errorHandlerFactory);
        RecordMessageConverter converter = null;
        return provider.build(consumerFactory, kafkaTemplate, converter);
    }
}


