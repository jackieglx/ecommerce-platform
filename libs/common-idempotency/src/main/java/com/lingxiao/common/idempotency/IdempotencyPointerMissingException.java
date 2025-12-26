package com.lingxiao.common.idempotency;

public class IdempotencyPointerMissingException extends RuntimeException {
    public IdempotencyPointerMissingException(String message) {
        super(message);
    }
}


