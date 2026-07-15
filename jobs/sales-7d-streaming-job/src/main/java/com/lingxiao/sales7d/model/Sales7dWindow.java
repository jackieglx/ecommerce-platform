package com.lingxiao.sales7d.model;

/** Fixed-size, processing-time seven-day window maintenance. */
public final class Sales7dWindow {

    public static final int BUCKETS = 168;

    private Sales7dWindow() {
    }

    /**
     * Removes every bucket outside [currentHour - 167, currentHour].
     * The fixed 168-slot scan makes long-idle state catch up correctly in one call.
     */
    public static AdvanceResult removeExpiredBuckets(long[] bucketVal, long[] slotHour,
                                                      long sum7d, long currentHour) {
        if (bucketVal.length != BUCKETS || slotHour.length != BUCKETS) {
            throw new IllegalArgumentException("Sales7d state must contain 168 hourly buckets");
        }

        long windowStart = currentHour - (BUCKETS - 1L);
        boolean changed = false;
        for (int i = 0; i < BUCKETS; i++) {
            if (slotHour[i] != 0 && slotHour[i] < windowStart) {
                sum7d -= bucketVal[i];
                bucketVal[i] = 0;
                slotHour[i] = 0;
                changed = true;
            } else if (slotHour[i] == 0 && bucketVal[i] != 0) {
                sum7d -= bucketVal[i];
                bucketVal[i] = 0;
                changed = true;
            }
        }
        return new AdvanceResult(sum7d, changed);
    }

    public record AdvanceResult(long sum7d, boolean changed) {
    }
}
