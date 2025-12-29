package com.lingxiao.catalog.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.lingxiao.catalog.config.CatalogCacheProperties;
import com.lingxiao.catalog.domain.model.Sku;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class SkuCache {

    private final Cache<String, CacheValue> l1;
    private final RedisTemplate<String, byte[]> redis; // L2 存 bytes
    private final CacheValueCodec codec;               // JSON encode/decode

    private final CatalogCacheKeys keys;
    private final CatalogCacheProperties props;

    public SkuCache(Cache<String, CacheValue> l1,
                    RedisTemplate<String, byte[]> redis,
                    CacheValueCodec codec,
                    CatalogCacheKeys keys,
                    CatalogCacheProperties props) {
        this.l1 = l1;
        this.redis = redis;
        this.codec = codec;
        this.keys = keys;
        this.props = props;
    }

    public CacheValue getValue(String skuId) {
        CacheValue v = l1.getIfPresent(skuId);
        if (v != null) return v;

        String redisKey = keys.sku(skuId);
        byte[] bytes = redis.opsForValue().get(redisKey);

        CacheValue cached = decodeOrEvict(redisKey, bytes);
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

        // 1) L1
        for (String id : ids) {
            CacheValue v = l1.getIfPresent(id);
            if (v != null) {
                if (v.isNegative()) negatives.add(id);
                else if (v.sku() != null) hits.add(v.sku());
            } else {
                l1Miss.add(id);
            }
        }

        // 2) L2 multiGet
        List<String> finalMiss = new ArrayList<>();
        if (!l1Miss.isEmpty()) {
            List<String> redisKeys = l1Miss.stream().map(keys::sku).toList();
            List<byte[]> redisVals = redis.opsForValue().multiGet(redisKeys);

            if (redisVals == null) {
                finalMiss.addAll(l1Miss);
                return new BatchLookupResult(hits, negatives, finalMiss);
            }

            int n = Math.min(l1Miss.size(), redisVals.size());
            for (int i = 0; i < n; i++) {
                String skuId = l1Miss.get(i);
                String redisKey = redisKeys.get(i);
                byte[] bytes = redisVals.get(i);

                CacheValue cv = decodeOrEvict(redisKey, bytes);
                if (cv != null) {
                    l1.put(skuId, cv);
                    if (cv.isNegative()) negatives.add(skuId);
                    else if (cv.sku() != null) hits.add(cv.sku());
                } else {
                    finalMiss.add(skuId);
                }
            }

            // 防御：如果 redisVals 比预期短（极少见），剩余的都算 miss
            for (int i = n; i < l1Miss.size(); i++) {
                finalMiss.add(l1Miss.get(i));
            }
        }

        return new BatchLookupResult(hits, negatives, finalMiss);
    }

    public void put(Sku sku) {
        CacheValue v = buildValue(sku, false);
        l1.put(sku.skuId(), v);

        String redisKey = keys.sku(sku.skuId());
        Duration ttl = props.getL2Ttl().plusMillis(jitter());
        redis.opsForValue().set(redisKey, codec.encode(v), ttl);
    }

    public void putNotFound(String skuId) {
        CacheValue v = buildValue(null, true);
        l1.put(skuId, v);

        String redisKey = keys.sku(skuId);
        Duration ttl = props.getL2NegativeTtl().plusMillis(jitter());
        redis.opsForValue().set(redisKey, codec.encode(v), ttl);
    }

    public void invalidateL2(String skuId) {
        redis.delete(keys.sku(skuId)); // best-effort
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

    /**
     * decode 失败就自愈：删掉坏数据，避免后续一直炸/一直 miss
     */
    private CacheValue decodeOrEvict(String redisKey, byte[] bytes) {
        if (bytes == null) return null;

        CacheValue cv = codec.decode(bytes);
        if (cv == null) {
            try {
                redis.delete(redisKey);
            } catch (Exception ignored) {}
        }
        return cv;
    }
}
