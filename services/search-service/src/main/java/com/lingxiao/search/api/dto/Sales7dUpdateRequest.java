package com.lingxiao.search.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

public record Sales7dUpdateRequest(
        @NotEmpty @Valid List<Sales7dUpdate> updates
) {
    public record Sales7dUpdate(
            String skuId,
            long sales7d,
            long sales7dHour,
            Instant sales7dUpdatedAt
    ) {
    }
}

