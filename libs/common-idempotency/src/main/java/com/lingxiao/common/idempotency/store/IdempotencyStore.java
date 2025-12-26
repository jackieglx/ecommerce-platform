package com.lingxiao.common.idempotency.store;

import java.time.Duration;
import java.util.Optional;

public interface IdempotencyStore {
    AcquireOutcome acquire(String key, String token, Duration processingTtl, String payload);

    boolean markDone(String key, String token, Duration doneTtl, String resultPointer, String payload);

    void release(String key, String token);

    Optional<String> getDonePointer(String key);
}

