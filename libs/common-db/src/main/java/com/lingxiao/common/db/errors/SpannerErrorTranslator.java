package com.lingxiao.common.db.errors;

import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.SpannerException;

public class SpannerErrorTranslator {

    public DbException translate(Throwable throwable) {
        if (throwable instanceof DbException dbEx) {
            return dbEx;
        }

        if (throwable instanceof SpannerException spannerEx) {
            ErrorCode code = spannerEx.getErrorCode();
            return switch (code) {
                case ALREADY_EXISTS -> new DuplicateKeyException("duplicate key", spannerEx);
                case NOT_FOUND -> new NotFoundException("not found", spannerEx);
                case ABORTED, DEADLINE_EXCEEDED -> new DbException("retryable spanner error: " + code, spannerEx);
                case PERMISSION_DENIED, UNAUTHENTICATED -> new DbException("spanner auth error: " + code, spannerEx);
                default -> new DbException("spanner error: " + code, spannerEx);
            };
        }

        return new DbException("db error", throwable);
    }
}

