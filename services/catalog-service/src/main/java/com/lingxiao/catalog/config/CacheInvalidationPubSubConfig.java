package com.lingxiao.catalog.config;

import com.lingxiao.catalog.infrastructure.cache.pubsub.CacheInvalidationSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class CacheInvalidationPubSubConfig {

    @Bean
    public Object cacheInvalidationSubscription(RedisMessageListenerContainer container,
                                                CacheInvalidationSubscriber subscriber,
                                                CatalogCacheProperties props) {
        container.addMessageListener(subscriber, new PatternTopic(props.getInvalidationChannel()));
        return new Object(); // placeholder bean to ensure registration executes
    }
}


