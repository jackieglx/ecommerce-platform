package com.lingxiao.catalog.application.query;

import com.lingxiao.catalog.api.dto.SkuResponse;
import com.lingxiao.catalog.application.mapper.SkuMapper;
import com.lingxiao.catalog.infrastructure.cache.SingleFlight;
import com.lingxiao.catalog.infrastructure.cache.SkuCache;
import com.lingxiao.catalog.infrastructure.db.SkuRepository;
import com.lingxiao.common.db.errors.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class SkuQueryService {

    private static final int MAX_BATCH = 200;

    private final SkuRepository repository;
    private final SkuCache cache;
    private final SingleFlight singleFlight;
    private final SkuMapper mapper;

    public SkuQueryService(SkuRepository repository,
                           SkuCache cache,
                           SingleFlight singleFlight,
                           SkuMapper mapper) {
        this.repository = repository;
        this.cache = cache;
        this.singleFlight = singleFlight;
        this.mapper = mapper;
    }

    public SkuResponse get(String skuId) {
        var cv = cache.getValue(skuId);
        if (cv != null) {
            if (cv.isNegative()) {
                throw new NotFoundException("sku not found: " + skuId);
            }
            return mapper.toResponse(cv.sku());
        }
        var loaded = singleFlight.execute(skuId, () -> loadAndCache(skuId));
        if (loaded == null || loaded.sku() == null) {
            throw new NotFoundException("sku not found: " + skuId);
        }
        return mapper.toResponse(loaded.sku());
    }

    public List<SkuResponse> batchGet(List<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        List<String> orderedDistinct = new ArrayList<>(new LinkedHashSet<>(skuIds));
        if (orderedDistinct.size() > MAX_BATCH) {
            throw new IllegalArgumentException("too many ids, max " + MAX_BATCH);
        }

        var lookup = cache.batchLookup(orderedDistinct);
        List<com.lingxiao.catalog.domain.model.Sku> hits = new ArrayList<>(lookup.getHits());

        List<String> misses = lookup.getMisses();
        if (!misses.isEmpty()) {
            List<com.lingxiao.catalog.domain.model.Sku> db = repository.batchGet(misses);
            hits.addAll(db);
            Set<String> foundIds = db.stream().map(com.lingxiao.catalog.domain.model.Sku::skuId).collect(java.util.stream.Collectors.toSet());
            db.forEach(cache::put);
            for (String id : misses) {
                if (!foundIds.contains(id)) {
                    cache.putNotFound(id);
                }
            }
        }
        // negatives already handled; order by requested list
        return orderedDistinct.stream()
                .map(id -> hits.stream().filter(s -> s.skuId().equals(id)).findFirst().orElse(null))
                .filter(s -> s != null)
                .map(mapper::toResponse)
                .toList();
    }

    private com.lingxiao.catalog.infrastructure.cache.CacheValue loadAndCache(String skuId) {
        try {
            var sku = repository.get(skuId);
            cache.put(sku);
            return new com.lingxiao.catalog.infrastructure.cache.CacheValue(sku, false);
        } catch (NotFoundException ex) {
            cache.putNotFound(skuId);
            return new com.lingxiao.catalog.infrastructure.cache.CacheValue(null, true);
        }
    }
}


