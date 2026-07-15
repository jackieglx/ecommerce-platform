package com.lingxiao.sales7d.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sales7dWindowTest {

    @Test
    void retainsTheOldestHourStillInsideTheWindow() {
        long[] buckets = new long[Sales7dWindow.BUCKETS];
        long[] slotHours = new long[Sales7dWindow.BUCKETS];
        long currentHour = 10_000;
        long oldestIncludedHour = currentHour - 167;
        int index = (int) Math.floorMod(oldestIncludedHour, Sales7dWindow.BUCKETS);
        buckets[index] = 3;
        slotHours[index] = oldestIncludedHour;

        Sales7dWindow.AdvanceResult result =
                Sales7dWindow.removeExpiredBuckets(buckets, slotHours, 3, currentHour);

        assertEquals(3, result.sum7d());
        assertFalse(result.changed());
    }

    @Test
    void removesAnExpiredBucketAfterAnIdleSkuIsWoken() {
        long[] buckets = new long[Sales7dWindow.BUCKETS];
        long[] slotHours = new long[Sales7dWindow.BUCKETS];
        long currentHour = 10_000;
        long expiredHour = currentHour - 168;
        int index = (int) Math.floorMod(expiredHour, Sales7dWindow.BUCKETS);
        buckets[index] = 7;
        slotHours[index] = expiredHour;

        Sales7dWindow.AdvanceResult result =
                Sales7dWindow.removeExpiredBuckets(buckets, slotHours, 7, currentHour);

        assertEquals(0, result.sum7d());
        assertTrue(result.changed());
        assertEquals(0, buckets[index]);
        assertEquals(0, slotHours[index]);
    }
}
