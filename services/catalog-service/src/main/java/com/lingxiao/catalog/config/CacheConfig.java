package com.lingxiao.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lingxiao.catalog.infrastructure.cache.CacheValue;
import com.lingxiao.catalog.infrastructure.cache.CatalogCacheKeys;
import com.lingxiao.catalog.infrastructure.cache.SkuCache;
import com.lingxiao.catalog.infrastructure.cache.SingleFlight;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableConfigurationProperties(CatalogCacheProperties.class)
public class CacheConfig {

    @Bean
    public com.github.benmanes.caffeine.cache.Cache<String, CacheValue> l1Cache(CatalogCacheProperties props) {
        return Caffeine.newBuilder()
                .maximumSize(props.getL1MaxSize())
                .expireAfter(new com.github.benmanes.caffeine.cache.Expiry<String, CacheValue>() {
                    @Override
                    public long expireAfterCreate(String key, CacheValue value, long currentTime) {
                        java.time.Duration ttl = value.isNegative() ? props.getL1NegativeTtl() : props.getL1Ttl();
                        return ttl.toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, CacheValue value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, CacheValue value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    @Bean
    public RedisTemplate<String, CacheValue> cacheRedisTemplate(
            RedisConnectionFactory factory,
            ObjectMapper objectMapper
    ) {
        RedisTemplate<String, CacheValue> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        ObjectMapper om = objectMapper.copy();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer(om));

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public CatalogCacheKeys catalogCacheKeys(CatalogCacheProperties props) {
        return new CatalogCacheKeys(props);
    }

    @Bean
    public SingleFlight singleFlight(CatalogCacheProperties props) {
        return new SingleFlight(props.getSingleFlightTimeout());
    }

    @Bean
    public SkuCache skuCache(com.github.benmanes.caffeine.cache.Cache<String, CacheValue> l1,
                             RedisTemplate<String, CacheValue> cacheRedisTemplate,
                             CatalogCacheKeys keys,
                             CatalogCacheProperties props) {
        return new SkuCache(l1, cacheRedisTemplate, keys, props);
    }
}


