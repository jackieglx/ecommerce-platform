package com.lingxiao.catalog.infrastructure.cache;

public class SingleFlightTimeoutException extends RuntimeException {
    public SingleFlightTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}


