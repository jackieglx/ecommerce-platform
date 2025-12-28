package com.lingxiao.catalog.application.mapper;

import com.lingxiao.catalog.api.dto.SkuResponse;
import com.lingxiao.catalog.domain.model.Sku;
import org.springframework.stereotype.Component;

@Component
public class SkuMapper {
    public SkuResponse toResponse(Sku sku) {
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


