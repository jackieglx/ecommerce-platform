package com.lingxiao.common.db.errors;

public class DuplicateKeyException extends DbException {
    public DuplicateKeyException(String message) {
        super(message);
    }

    public DuplicateKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}

