package com.lingxiao.catalog.application;

import com.lingxiao.catalog.api.dto.CreateSkuRequest;
import com.lingxiao.catalog.api.dto.SkuResponse;
import com.lingxiao.catalog.domain.model.Sku;
import com.lingxiao.catalog.infrastructure.db.SkuRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SkuAppService {

    private static final int MAX_BATCH = 200;

    private final SkuRepository repository;

    public SkuAppService(SkuRepository repository) {
        this.repository = repository;
    }

    public SkuResponse create(CreateSkuRequest request) {
        Sku sku = repository.create(request);
        return toResponse(sku);
    }

    public SkuResponse get(String skuId) {
        return toResponse(repository.get(skuId));
    }

    public List<SkuResponse> batchGet(List<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        List<String> orderedDistinct = new ArrayList<>(new LinkedHashSet<>(skuIds));
        if (orderedDistinct.size() > MAX_BATCH) {
            throw new IllegalArgumentException("too many ids, max " + MAX_BATCH);
        }

        List<Sku> found = repository.batchGet(orderedDistinct);
        Map<String, Sku> byId = found.stream().collect(Collectors.toMap(Sku::skuId, s -> s));

        List<SkuResponse> result = new ArrayList<>();
        for (String id : orderedDistinct) {
            Sku s = byId.get(id);
            if (s != null) {
                result.add(toResponse(s));
            }
        }
        return result;
    }

    private SkuResponse toResponse(Sku sku) {
        return new SkuResponse(
                sku.skuId(),
                sku.productId(),
                sku.title(),
                sku.status(),
                sku.brand(),
                sku.priceCents(),
                sku.currency(),
                sku.createdAt(),
                sku.updatedAt()
        );
    }
}

