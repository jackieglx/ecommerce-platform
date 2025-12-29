package com.lingxiao.catalog.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.lingxiao.catalog.infrastructure.cache.CacheValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public Object caffeineMetrics(MeterRegistry registry,
                                  Cache<String, CacheValue> l1Cache) {
        CaffeineCacheMetrics.monitor(registry, l1Cache, "catalog_l1");
        return new Object();
    }
}


