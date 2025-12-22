package com.lingxiao.search.config;

import com.lingxiao.common.redis.IdempotencyStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class IdempotencyConfig {

    @Bean
    public IdempotencyStore idempotencyStore(StringRedisTemplate redisTemplate) {
        return new IdempotencyStore(redisTemplate);
    }
}

