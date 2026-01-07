package com.lingxiao.sales7d.model;

import java.io.Serializable;
import java.util.Arrays;

/**
 * State for 7-day sales aggregation per SKU.
 * Maintains 168 hourly buckets (7 days * 24 hours) + aggregated sum.
 */
public class Sales7dState implements Serializable {
    // 168 hourly buckets (7 days * 24 hours)
    // bucketVal[i] = sales quantity for hour where (epochHour % 168) == i
    private final long[] bucketVal;
    // slotHour[i] = the actual epoch hour that bucketVal[i] represents
    // Used to detect bucket reuse: when slotHour[i] != paidAtHour, the bucket is being reused
    private final long[] slotHour;
    // Sum of all 168 buckets
    private final long sum7d;
    // Whether this SKU has been updated and needs to be flushed
    private final boolean dirty;
    // Last hour when we emitted updates for this SKU (epoch hour)
    private final long lastEmitHour;
    // Last hour when we advanced the window (for cleanup of old buckets) (epoch hour)
    private final long lastAdvancedHour;

    public Sales7dState() {
        this(new long[168], new long[168], 0L, false, 0L, 0L);
    }

    public Sales7dState(long[] bucketVal, long[] slotHour, long sum7d, boolean dirty,
                       long lastEmitHour, long lastAdvancedHour) {
        // Accept the array reference directly (caller is responsible for not modifying it after state update)
        // For new state creation, we still copy to ensure immutability
        this.bucketVal = bucketVal != null ? bucketVal : new long[168];
        this.slotHour = slotHour != null ? slotHour : new long[168];
        this.sum7d = sum7d;
        this.dirty = dirty;
        this.lastEmitHour = lastEmitHour;
        this.lastAdvancedHour = lastAdvancedHour;
    }

    /**
     * Returns the bucket array reference directly (not a copy) for efficient in-place modification.
     * The caller should not modify this array after passing it to state.update().
     */
    public long[] getBucketVal() {
        return bucketVal;
    }

    /**
     * Returns the slot hour array reference directly (not a copy) for efficient in-place modification.
     * The caller should not modify this array after passing it to state.update().
     */
    public long[] getSlotHour() {
        return slotHour;
    }

    /**
     * Creates a new state with a copy of both bucket and slotHour arrays.
     * Use this when you need to ensure the state is truly immutable.
     */
    public Sales7dState withCopy() {
        return new Sales7dState(
                Arrays.copyOf(bucketVal, 168),
                Arrays.copyOf(slotHour, 168),
                sum7d, dirty, lastEmitHour, lastAdvancedHour);
    }

    /**
     * Creates a new state with a copy of the bucket array.
     * Use this when you need to ensure the state is truly immutable.
     * @deprecated Use withCopy() instead to copy both arrays
     */
    @Deprecated
    public Sales7dState withBucketValCopy() {
        return new Sales7dState(
                Arrays.copyOf(bucketVal, 168),
                Arrays.copyOf(slotHour, 168),
                sum7d, dirty, lastEmitHour, lastAdvancedHour);
    }

    public long getSum7d() {
        return sum7d;
    }

    public boolean isDirty() {
        return dirty;
    }

    public long getLastEmitHour() {
        return lastEmitHour;
    }

    public long getLastAdvancedHour() {
        return lastAdvancedHour;
    }
}

