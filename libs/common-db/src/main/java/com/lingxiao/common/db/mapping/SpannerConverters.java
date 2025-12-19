package com.lingxiao.common.db.mapping;

import com.google.cloud.Timestamp;

import java.math.BigDecimal;
import java.time.Instant;

public final class SpannerConverters {

    private SpannerConverters() {
    }

    public static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }

    public static Instant toInstant(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    public static com.google.cloud.spanner.Value toNumeric(BigDecimal val) {
        return val == null ? com.google.cloud.spanner.Value.numeric(null) : com.google.cloud.spanner.Value.numeric(val);
    }
}

