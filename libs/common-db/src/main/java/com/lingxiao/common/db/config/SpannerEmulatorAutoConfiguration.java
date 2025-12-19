package com.lingxiao.common.db.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpannerProperties.class)
@ConditionalOnProperty(prefix = "spanner", name = "emulator-enabled", havingValue = "true")
@AutoConfigureBefore(SpannerAutoConfiguration.class)
public class SpannerEmulatorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SpannerEmulatorAutoConfiguration.class);

    @Bean
    public SpannerOptionsConfigurer emulatorHostConfigurer(SpannerProperties properties) {
        return builder -> {
            builder.setEmulatorHost(properties.getEmulatorHost());
            log.info("Spanner emulator enabled at {}", properties.getEmulatorHost());
        };
    }
}

