package com.lingxiao.search.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record IndexRequest(
        @NotEmpty List<String> skuIds
) {
}

