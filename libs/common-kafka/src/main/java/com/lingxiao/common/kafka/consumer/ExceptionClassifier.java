package com.lingxiao.common.kafka.consumer;

import com.lingxiao.common.idempotency.IdempotencyCompletedException;
import com.lingxiao.common.idempotency.IdempotencyInProgressException;
import com.lingxiao.common.idempotency.IdempotencyMarkDoneFailedException;
import com.lingxiao.common.idempotency.IdempotencyPayloadMismatchException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.validation.BindException;

public class ExceptionClassifier {

    public boolean isRetryable(Throwable ex) {
        Throwable cause = unwrap(ex);
        if (cause instanceof IdempotencyPayloadMismatchException) {
            return false;
        }
        if (cause instanceof IdempotencyCompletedException) {
            return false;
        }
        if (cause instanceof SerializationException) {
            return false;
        }
        if (cause instanceof MessageConversionException) {
            return false;
        }
        if (cause instanceof BindException) {
            return false;
        }
        if (cause instanceof IllegalArgumentException) {
            return false;
        }
        return true;
    }

    public boolean isInProgress(Throwable ex) {
        return unwrap(ex) instanceof IdempotencyInProgressException;
    }

    public boolean isMarkDoneFailed(Throwable ex) {
        return unwrap(ex) instanceof IdempotencyMarkDoneFailedException;
    }

    private Throwable unwrap(Throwable ex) {
        if (ex instanceof ListenerExecutionFailedException lefe && lefe.getCause() != null) {
            return lefe.getCause();
        }
        return ex;
    }
}


