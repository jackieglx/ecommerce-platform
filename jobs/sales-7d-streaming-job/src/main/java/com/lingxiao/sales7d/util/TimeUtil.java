package com.lingxiao.sales7d.util;

import java.time.Instant;

/**
 * Utility class for time calculations in 7-day sales aggregation.
 */
public class TimeUtil {

    private static final long MILLIS_PER_HOUR = 3600000L;

    /**
     * Converts epoch milliseconds to epoch hours (floor division).
     */
    public static long epochHour(long epochMillis) {
        return epochMillis / MILLIS_PER_HOUR;
    }

    /**
     * Converts current time to epoch hours.
     */
    public static long currentHour() {
        return epochHour(System.currentTimeMillis());
    }

    /**
     * Floors the given epoch milliseconds to the nearest hour boundary.
     */
    public static long floorToHour(long epochMillis) {
        return epochHour(epochMillis);
    }

    /**
     * Parses an ISO-8601 timestamp string and returns the epoch hour.
     * 
     * @param isoTimestamp ISO-8601 timestamp string (e.g., "2024-01-01T12:00:00Z")
     * @return epoch hour, or Long.MIN_VALUE if parsing fails
     */
    public static long parsePaidAtHour(String isoTimestamp) {
        try {
            return epochHour(Instant.parse(isoTimestamp).toEpochMilli());
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }
}

