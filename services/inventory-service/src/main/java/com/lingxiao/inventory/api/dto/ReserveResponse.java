package com.lingxiao.inventory.api.dto;

import java.util.List;

public record ReserveResponse(
        List<String> reserved,
        List<String> missing
) {
}

