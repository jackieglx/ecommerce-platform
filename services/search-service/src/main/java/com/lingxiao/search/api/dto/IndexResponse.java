package com.lingxiao.search.api.dto;

import java.util.List;

public record IndexResponse(
        int requested,
        int indexed,
        List<String> missing
) {
}

