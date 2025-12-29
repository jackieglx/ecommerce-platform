package com.lingxiao.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lingxiao.catalog.infrastructure.cache.*;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;


@Configuration
@EnableConfigurationProperties(CatalogCacheProperties.class)
public class CacheConfig {

    @Bean
    public com.github.benmanes.caffeine.cache.Cache<String, CacheValue> l1Cache(CatalogCacheProperties props) {
        return Caffeine.newBuilder()
                .recordStats()
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
    public CacheValueCodec cacheValueCodec(ObjectMapper base) {
        ObjectMapper om = base.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new CacheValueCodec(om);
    }


    @Bean
    public RedisTemplate<String, byte[]> cacheBytesRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setHashValueSerializer(RedisSerializer.byteArray());

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
                             RedisTemplate<String, byte[]> cacheBytesRedisTemplate,
                             CacheValueCodec cacheValueCodec,
                             CatalogCacheKeys keys,
                             CatalogCacheProperties props) {
        return new SkuCache(l1, cacheBytesRedisTemplate, cacheValueCodec, keys, props);
    }



}


