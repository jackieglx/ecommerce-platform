package com.lingxiao.catalog.infrastructure.cache.pubsub;

import com.lingxiao.catalog.config.CatalogCacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CacheInvalidationPublisher {

    private final StringRedisTemplate redis;
    private final CatalogCacheProperties props;

    public CacheInvalidationPublisher(StringRedisTemplate redis, CatalogCacheProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void publish(String skuId) {
        redis.convertAndSend(props.getInvalidationChannel(), skuId);
    }
}


