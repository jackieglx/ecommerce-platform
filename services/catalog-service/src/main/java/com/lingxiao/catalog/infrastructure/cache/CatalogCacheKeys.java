package com.lingxiao.catalog.infrastructure.cache;

import com.lingxiao.catalog.config.CatalogCacheProperties;

public class CatalogCacheKeys {
    private final String skuPrefix;

    public CatalogCacheKeys(CatalogCacheProperties props) {
        this.skuPrefix = props.getSkuPrefix();
    }

    public String sku(String skuId) {
        return skuPrefix + skuId;
    }
}


