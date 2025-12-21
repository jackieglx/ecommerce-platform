package com.lingxiao.search.api.dto;

import java.util.List;

public record SearchResult(
        long total,
        List<SearchResponseItem> items
) {
}


