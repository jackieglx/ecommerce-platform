package com.lingxiao.catalog.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.lingxiao.catalog.config.CatalogCacheProperties;
import com.lingxiao.catalog.domain.model.Sku;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class SkuCache {

    private final Cache<String, CacheValue> l1;
    private final RedisTemplate<String, CacheValue> redis;
    private final CatalogCacheKeys keys;
    private final CatalogCacheProperties props;

    public SkuCache(Cache<String, CacheValue> l1,
                    RedisTemplate<String, CacheValue> redis,
                    CatalogCacheKeys keys,
                    CatalogCacheProperties props) {
        this.l1 = l1;
        this.redis = redis;
        this.keys = keys;
        this.props = props;
    }

    public CacheValue getValue(String skuId) {
        CacheValue v = l1.getIfPresent(skuId);
        if (v != null) {
            return v;
        }
        String key = keys.sku(skuId);
        CacheValue cached = redis.opsForValue().get(key);
        if (cached != null) {
            l1.put(skuId, cached);
            return cached;
        }
        return null;
    }

    public BatchLookupResult batchLookup(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new BatchLookupResult(List.of(), List.of(), List.of());
        }
        List<Sku> hits = new ArrayList<>();
        List<String> negatives = new ArrayList<>();
        List<String> l1Miss = new ArrayList<>();

        for (String id : ids) {
            CacheValue v = l1.getIfPresent(id);
            if (v != null) {
                if (v.isNegative()) {
                    negatives.add(id);
                } else if (v.sku() != null) {
                    hits.add(v.sku());
                }
                continue;
            }
            l1Miss.add(id);
        }

        List<String> finalMiss = new ArrayList<>();

        if (!l1Miss.isEmpty()) {
            List<String> redisKeys = l1Miss.stream().map(keys::sku).toList();
            List<CacheValue> redisVals = redis.opsForValue().multiGet(redisKeys);
            for (int i = 0; i < l1Miss.size(); i++) {
                CacheValue cv = redisVals.get(i);
                if (cv != null) {
                    l1.put(l1Miss.get(i), cv);
                    if (cv.isNegative()) {
                        negatives.add(l1Miss.get(i));
                    } else if (cv.sku() != null) {
                        hits.add(cv.sku());
                    }
                } else {
                    finalMiss.add(l1Miss.get(i));
                }
            }
        }

        return new BatchLookupResult(hits, negatives, finalMiss);
    }

    public void put(Sku sku) {
        long j = jitter();
        CacheValue v = buildValue(sku, false);
        l1.put(sku.skuId(), v);
        redis.opsForValue().set(keys.sku(sku.skuId()), v, props.getL2Ttl().plusMillis(j).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void putNotFound(String skuId) {
        long j = jitter();
        CacheValue v = buildValue(null, true);
        l1.put(skuId, v);
        redis.opsForValue().set(keys.sku(skuId), v, props.getL2NegativeTtl().plusMillis(j).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void invalidateL2(String skuId) {
        redis.delete(keys.sku(skuId)); // key may not exist; best-effort
    }

    public void invalidateL1(String skuId) {
        l1.invalidate(skuId);
    }

    private CacheValue buildValue(Sku sku, boolean negative) {
        return new CacheValue(sku, negative);
    }

    private long jitter() {
        long j = props.getL2JitterMs();
        return j <= 0 ? 0 : ThreadLocalRandom.current().nextLong(j);
    }
}


