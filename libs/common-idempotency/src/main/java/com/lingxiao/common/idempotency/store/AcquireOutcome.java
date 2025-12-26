package com.lingxiao.common.idempotency.store;

import java.util.Optional;

public record AcquireOutcome(AcquireResult result, Optional<String> pointer) {
    public static AcquireOutcome of(AcquireResult result) {
        return new AcquireOutcome(result, Optional.empty());
    }

    public static AcquireOutcome of(AcquireResult result, String pointer) {
        return new AcquireOutcome(result, Optional.ofNullable(pointer));
    }
}


