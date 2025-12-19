package com.lingxiao.common.db.repo;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.TransactionContext;
import com.lingxiao.common.db.errors.SpannerErrorTranslator;
import com.lingxiao.common.db.tx.TxRunner;

import java.util.function.Function;

public abstract class BaseRepositorySupport {

    protected final DatabaseClient databaseClient;
    protected final SpannerErrorTranslator translator;
    protected final TxRunner txRunner;

    protected BaseRepositorySupport(DatabaseClient databaseClient,
                                    SpannerErrorTranslator translator,
                                    TxRunner txRunner) {
        this.databaseClient = databaseClient;
        this.translator = translator;
        this.txRunner = txRunner;
    }

    protected <T> T inReadOnly(Function<ReadOnlyTransaction, T> work) {
        return txRunner.runReadOnly(work);
    }

    protected <T> T inReadWrite(Function<TransactionContext, T> work) {
        return txRunner.runReadWrite(work);
    }
}

