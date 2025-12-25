package com.lingxiao.common.idempotency;

public class IdempotencyKeyResolveException extends RuntimeException {
    public IdempotencyKeyResolveException(String message, Throwable cause) {
        super(message, cause);
    }

    public IdempotencyKeyResolveException(String message) {
        super(message);
    }
}

