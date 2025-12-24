package com.lingxiao.common.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@ConditionalOnClass({RedisConnectionFactory.class, StringRedisTemplate.class})
public class RedisConfig {

    @Bean
    @ConditionalOnMissingBean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore idempotencyStore(StringRedisTemplate stringRedisTemplate) {
        return new IdempotencyStore(stringRedisTemplate);
    }
}
