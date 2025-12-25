package com.lingxiao.common.idempotency.autoconfigure;

import com.lingxiao.common.idempotency.aop.DurationParser;
import com.lingxiao.common.idempotency.aop.IdempotentAspect;
import com.lingxiao.common.idempotency.aop.IdempotencyNamespaceProvider;
import com.lingxiao.common.idempotency.aop.SpelKeyResolver;
import com.lingxiao.common.idempotency.store.IdempotencyStore;
import com.lingxiao.common.idempotency.store.RedisIdempotencyStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore idempotencyStore(StringRedisTemplate redisTemplate) {
        return new RedisIdempotencyStore(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpelKeyResolver spelKeyResolver() {
        return new SpelKeyResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public DurationParser durationParser() {
        return new DurationParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyNamespaceProvider idempotencyNamespaceProvider(org.springframework.core.env.Environment env) {
        return new IdempotencyNamespaceProvider(env);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentAspect idempotentAspect(IdempotencyStore store,
                                             SpelKeyResolver keyResolver,
                                             DurationParser durationParser,
                                             IdempotencyNamespaceProvider namespaceProvider) {
        return new IdempotentAspect(store, keyResolver, durationParser, namespaceProvider);
    }
}

