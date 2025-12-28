package com.lingxiao.catalog.infrastructure.cache;

import com.lingxiao.catalog.domain.model.Sku;

import java.util.List;

public record BatchLookupResult(List<Sku> hits, List<String> negatives, List<String> misses) {
    public List<Sku> getHits() { return hits; }
    public List<String> getNegatives() { return negatives; }
    public List<String> getMisses() { return misses; }
}


