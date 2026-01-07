package com.lingxiao.sales7d.model;

import java.io.Serializable;
import java.time.Instant;

/**
 * Output model for 7-day sales update to search-service.
 */
public class Sales7dUpdate implements Serializable {
    private final String skuId;
    private final long sales7d;
    private final long sales7dHour;
    private final Instant sales7dUpdatedAt;

    public Sales7dUpdate(String skuId, long sales7d, long sales7dHour, Instant sales7dUpdatedAt) {
        this.skuId = skuId;
        this.sales7d = sales7d;
        this.sales7dHour = sales7dHour;
        this.sales7dUpdatedAt = sales7dUpdatedAt;
    }

    public String getSkuId() {
        return skuId;
    }

    public long getSales7d() {
        return sales7d;
    }

    public long getSales7dHour() {
        return sales7dHour;
    }

    public Instant getSales7dUpdatedAt() {
        return sales7dUpdatedAt;
    }
}

