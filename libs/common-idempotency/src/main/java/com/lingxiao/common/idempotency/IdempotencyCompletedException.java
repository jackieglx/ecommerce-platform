package com.lingxiao.common.idempotency;

public class IdempotencyCompletedException extends RuntimeException {
    public IdempotencyCompletedException(String message) {
        super(message);
    }
}

