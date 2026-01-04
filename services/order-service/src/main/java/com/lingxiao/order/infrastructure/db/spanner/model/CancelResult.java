package com.lingxiao.order.infrastructure.db.spanner.model;

public enum CancelResult {
    CANCELLED,
    ALREADY_FINAL,
    NOT_FOUND,
    NOT_EXPIRED_YET,
    RETRYABLE_FAIL
}


