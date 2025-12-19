package com.lingxiao.common.db.ddl;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.spanner.admin.database.v1.UpdateDatabaseDdlMetadata;
import com.lingxiao.common.db.client.SpannerDatabaseProvider;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class DdlApplier {

    private final DatabaseAdminClient adminClient;
    private final SpannerDatabaseProvider provider;

    public DdlApplier(DatabaseAdminClient adminClient, SpannerDatabaseProvider provider) {
        this.adminClient = adminClient;
        this.provider = provider;
    }

    public void apply(List<String> statements) {
        if (statements == null || statements.isEmpty()) {
            return;
        }
        try {
            OperationFuture<Void, UpdateDatabaseDdlMetadata> op =
                    adminClient.updateDatabaseDdl(
                            provider.getInstanceId(),
                            provider.getDatabaseIdValue(),
                            statements,
                            null);
            op.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply DDL", e);
        }
    }
}

