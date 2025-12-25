package com.lingxiao.common.idempotency.store;

import java.time.Duration;

public interface IdempotencyStore {
    AcquireResult acquire(String key, String token, Duration processingTtl);

    boolean markDone(String key, String token, Duration doneTtl);

    void release(String key, String token);
}

