package com.lingxiao.catalog.infrastructure.db;

import com.lingxiao.catalog.api.dto.CreateSkuRequest;
import com.lingxiao.catalog.domain.model.Sku;

import java.util.List;

public interface SkuRepository {
    Sku create(CreateSkuRequest request);

    Sku get(String skuId);

    List<Sku> batchGet(List<String> skuIds);
}

