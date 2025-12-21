package com.lingxiao.catalog.messaging;

import com.lingxiao.contracts.events.SkuUpsertedEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public KafkaTemplate<String, SkuUpsertedEvent> kafkaTemplate(ProducerFactory<String, SkuUpsertedEvent> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public ProducerFactory<String, SkuUpsertedEvent> producerFactory(
            KafkaProperties properties) {
        Map<String, Object> configs = new HashMap<>(properties.buildProducerProperties());
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        DefaultKafkaProducerFactory<String, SkuUpsertedEvent> factory = new DefaultKafkaProducerFactory<>(configs);
        return factory;
    }
}

