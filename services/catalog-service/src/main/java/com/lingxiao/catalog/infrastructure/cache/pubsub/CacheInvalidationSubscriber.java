package com.lingxiao.catalog.infrastructure.cache.pubsub;

import com.lingxiao.catalog.config.CatalogCacheProperties;
import com.lingxiao.catalog.infrastructure.cache.SkuCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class CacheInvalidationSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationSubscriber.class);

    private final CatalogCacheProperties props;
    private final SkuCache cache;

    public CacheInvalidationSubscriber(CatalogCacheProperties props,
                                       SkuCache cache) {
        this.props = props;
        this.cache = cache;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String skuId = new String(message.getBody());
        cache.invalidateL1(skuId);
        log.debug("Invalidated L1 for skuId={} from channel={}", skuId, props.getInvalidationChannel());
    }
}


