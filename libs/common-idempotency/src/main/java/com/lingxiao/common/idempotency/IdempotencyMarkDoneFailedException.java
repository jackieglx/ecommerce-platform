package com.lingxiao.common.idempotency;

public class IdempotencyMarkDoneFailedException extends RuntimeException {
    public IdempotencyMarkDoneFailedException(String message) {
        super(message);
    }

    public IdempotencyMarkDoneFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}

