package com.lingxiao.common.db.tx;

import com.google.cloud.spanner.AbortedException;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;
import com.lingxiao.common.db.errors.DbException;
import com.lingxiao.common.db.errors.SpannerErrorTranslator;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

public class TxRunner {

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(50);

    private final DatabaseClient databaseClient;
    private final SpannerErrorTranslator translator;

    public TxRunner(DatabaseClient databaseClient, SpannerErrorTranslator translator) {
        this.databaseClient = databaseClient;
        this.translator = translator;
    }

    public <T> T runReadWrite(Function<TransactionContext, T> work) {
        int attempt = 0;
        while (true) {
            try {
                TransactionRunner runner = databaseClient.readWriteTransaction();
                return runner.run(work::apply);
            } catch (AbortedException aborted) {
                attempt++;
                if (attempt >= DEFAULT_MAX_ATTEMPTS) {
                    throw translator.translate(aborted);
                }
                sleepWithJitter(attempt);
            } catch (Exception ex) {
                throw translator.translate(ex);
            }
        }
    }

    public <T> T runReadOnly(Function<ReadOnlyTransaction, T> work) {
        try (ReadOnlyTransaction tx = databaseClient.singleUseReadOnlyTransaction()) {
            return work.apply(tx);
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }

    private void sleepWithJitter(int attempt) {
        long base = BASE_BACKOFF.toMillis() * (1L << Math.min(attempt, 10));
        long jitter = ThreadLocalRandom.current().nextLong(0, BASE_BACKOFF.toMillis());
        try {
            Thread.sleep(Math.min(base + jitter, 1_000L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DbException("Interrupted while backing off after aborted txn", ie);
        }
    }

    @FunctionalInterface
    public interface TransactionalOps {
        <T> T execute(Function<TransactionContext, T> work);
    }
}

