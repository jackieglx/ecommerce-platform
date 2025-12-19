package com.lingxiao.common.db.tx;

import com.google.cloud.Timestamp;

/**
 * Small helper to keep commit timestamp together.
 */
public record SpannerCommitTimestamp(Timestamp timestamp) {
}

