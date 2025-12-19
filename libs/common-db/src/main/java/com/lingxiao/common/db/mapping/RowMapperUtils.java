package com.lingxiao.common.db.mapping;

import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

public final class RowMapperUtils {

    private RowMapperUtils() {
    }

    public static Optional<String> getString(Struct row, String column) {
        return row.isNull(column) ? Optional.empty() : Optional.of(row.getString(column));
    }

    public static Optional<Long> getLong(Struct row, String column) {
        return row.isNull(column) ? Optional.empty() : Optional.of(row.getLong(column));
    }

    public static Optional<BigDecimal> getBigDecimal(Struct row, String column) {
        return row.isNull(column) ? Optional.empty() : Optional.of(row.getBigDecimal(column));
    }

    public static Optional<Instant> getInstant(Struct row, String column) {
        if (row.isNull(column)) {
            return Optional.empty();
        }
        var ts = row.getTimestamp(column);
        return Optional.of(Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()));
    }

    public static <T> Optional<T> mapSingle(ResultSet rs, Function<Struct, T> mapper) {
        if (!rs.next()) {
            return Optional.empty();
        }
        T value = mapper.apply(rs.getCurrentRowAsStruct());
        return Optional.of(value);
    }

    public static Value jsonValue(String json) {
        return json == null ? Value.json(null) : Value.json(json);
    }
}

