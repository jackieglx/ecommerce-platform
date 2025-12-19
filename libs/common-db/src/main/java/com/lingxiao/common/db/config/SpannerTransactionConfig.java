package com.lingxiao.common.db.config;

import com.lingxiao.common.db.tx.TxRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Transaction utilities wiring. We keep it thin: expose TxRunner and a simple
 * lambda-friendly wrapper for ad-hoc programmatic transactions.
 */
@Configuration(proxyBeanMethods = false)
public class SpannerTransactionConfig {

    @Bean
    public TxRunner.TransactionalOps transactionalOps(TxRunner txRunner) {
        return txRunner::runReadWrite;
    }
}

