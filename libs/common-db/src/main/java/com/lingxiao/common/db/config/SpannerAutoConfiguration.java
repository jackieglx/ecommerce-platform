package com.lingxiao.common.db.config;

import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.lingxiao.common.db.client.SpannerClientFactory;
import com.lingxiao.common.db.client.SpannerDatabaseProvider;
import com.lingxiao.common.db.errors.SpannerErrorTranslator;
import com.lingxiao.common.db.tx.TxRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpannerProperties.class)
@ConditionalOnClass(SpannerOptions.class)
public class SpannerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpannerDatabaseProvider spannerDatabaseProvider(SpannerProperties properties) {
        return new SpannerDatabaseProvider(
                properties.getProjectId(),
                properties.getInstanceId(),
                properties.getDatabaseId()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public SpannerOptions spannerOptions(SpannerProperties properties, List<SpannerOptionsConfigurer> configurers) {
        SpannerOptions.Builder builder = SpannerOptions.newBuilder()
                .setProjectId(properties.getProjectId());

        if (properties.isEmulatorEnabled()) {
            builder.setEmulatorHost(properties.getEmulatorHost());
        }

        if (properties.getChannelCount() > 0) {
            builder.setNumChannels(properties.getChannelCount());
        }

        for (SpannerOptionsConfigurer configurer : configurers) {
            configurer.accept(builder);
        }

        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public Spanner spanner(SpannerOptions options) {
        return options.getService();
    }

    @Bean
    @ConditionalOnMissingBean
    public DatabaseClient databaseClient(Spanner spanner, SpannerDatabaseProvider provider) {
        return spanner.getDatabaseClient(provider.getDatabaseId());
    }

    @Bean
    @ConditionalOnMissingBean
    public DatabaseAdminClient databaseAdminClient(Spanner spanner) {
        return spanner.getDatabaseAdminClient();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpannerClientFactory spannerClientFactory() {
        return new SpannerClientFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpannerErrorTranslator spannerErrorTranslator() {
        return new SpannerErrorTranslator();
    }

    @Bean
    @ConditionalOnMissingBean
    public TxRunner txRunner(DatabaseClient databaseClient, SpannerErrorTranslator translator) {
        return new TxRunner(databaseClient, translator);
    }
}

