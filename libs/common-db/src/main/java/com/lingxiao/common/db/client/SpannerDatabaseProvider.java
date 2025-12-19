package com.lingxiao.common.db.client;

import com.google.cloud.spanner.DatabaseId;

public class SpannerDatabaseProvider {

    private final String projectId;
    private final String instanceId;
    private final String databaseId;

    public SpannerDatabaseProvider(String projectId, String instanceId, String databaseId) {
        this.projectId = projectId;
        this.instanceId = instanceId;
        this.databaseId = databaseId;
    }

    public DatabaseId getDatabaseId() {
        return DatabaseId.of(projectId, instanceId, databaseId);
    }

    public String getProjectId() {
        return projectId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getDatabaseIdValue() {
        return databaseId;
    }
}

