package com.lingxiao.catalog.application;

import com.lingxiao.catalog.api.dto.CreateSkuRequest;
import com.lingxiao.catalog.api.dto.SkuResponse;
import com.lingxiao.catalog.domain.model.Sku;
import com.lingxiao.catalog.infrastructure.db.SkuRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SkuAppService {

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
        return repository.batchGet(skuIds).stream().map(this::toResponse).toList();
    }

    private SkuResponse toResponse(Sku sku) {
        return new SkuResponse(
                sku.skuId(),
                sku.productId(),
                sku.title(),
                sku.status(),
                sku.priceCents(),
                sku.currency(),
                sku.createdAt(),
                sku.updatedAt()
        );
    }
}

