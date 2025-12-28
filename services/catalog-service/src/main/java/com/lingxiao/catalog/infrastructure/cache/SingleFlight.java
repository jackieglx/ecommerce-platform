package com.lingxiao.catalog.infrastructure.cache;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class SingleFlight {

    private final Map<String, CompletableFuture<CacheValue>> inFlight = new ConcurrentHashMap<>();
    private final Duration timeout;

    public SingleFlight(Duration timeout) {
        this.timeout = timeout;
    }

    public CacheValue execute(String key, Supplier<CacheValue> loader) {
        CompletableFuture<CacheValue> newFuture = new CompletableFuture<>();
        CompletableFuture<CacheValue> future = inFlight.putIfAbsent(key, newFuture);
        boolean isLeader = (future == null);
        if (isLeader) {
            future = newFuture;
            try {
                CacheValue val = loader.get();
                future.complete(val);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                inFlight.remove(key, future);
            }
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            inFlight.remove(key, future); // best-effort allow next caller to become leader
            throw new SingleFlightTimeoutException("singleflight timeout key=" + key, te);
        } catch (Exception e) {
            throw unwrap(e);
        }
    }

    private RuntimeException unwrap(Exception e) {
        Throwable t = e;
        if (t instanceof java.util.concurrent.ExecutionException ee && ee.getCause() != null) {
            t = ee.getCause();
        } else if (t instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null) {
            t = ce.getCause();
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        if (t instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(t);
    }
}


