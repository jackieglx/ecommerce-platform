package com.lingxiao.catalog.infrastructure.cache;

import com.lingxiao.catalog.domain.model.Sku;

public record CacheValue(Sku sku, boolean negative) {
    public boolean isNegative() {
        return negative;
    }
}


