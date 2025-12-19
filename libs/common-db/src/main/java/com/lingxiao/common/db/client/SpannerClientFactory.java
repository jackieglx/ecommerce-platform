package com.lingxiao.common.db.client;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Spanner;

public class SpannerClientFactory {

    public DatabaseClient create(Spanner spanner, SpannerDatabaseProvider provider) {
        return spanner.getDatabaseClient(provider.getDatabaseId());
    }
}

