package com.lingxiao.common.kafka.autoconfigure;

import com.lingxiao.common.kafka.config.CommonKafkaProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.core.KafkaTemplate;

@AutoConfiguration
@EnableConfigurationProperties(CommonKafkaProperties.class)
@ConditionalOnClass(KafkaTemplate.class)
public class CommonKafkaAutoConfiguration {
}


