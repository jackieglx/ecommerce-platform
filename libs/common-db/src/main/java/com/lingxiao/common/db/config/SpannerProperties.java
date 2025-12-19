package com.lingxiao.common.db.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spanner")
public class SpannerProperties {

    /**
        * GCP project id.
        */
    private String projectId = "local-project";

    /**
        * Spanner instance id.
        */
    private String instanceId = "local-instance";

    /**
        * Spanner database id.
        */
    private String databaseId = "local-db";

    /**
        * Enable emulator (local/dev).
        */
    private boolean emulatorEnabled = false;

    /**
        * Emulator host, e.g. localhost:9010
        */
    private String emulatorHost = "localhost:9010";

    /**
        * Optional channel count, defaults to library value when null or &lt;=0.
        */
    private int channelCount = 0;

    public SpannerProperties() {
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getDatabaseId() {
        return databaseId;
    }

    public void setDatabaseId(String databaseId) {
        this.databaseId = databaseId;
    }

    public boolean isEmulatorEnabled() {
        return emulatorEnabled;
    }

    public void setEmulatorEnabled(boolean emulatorEnabled) {
        this.emulatorEnabled = emulatorEnabled;
    }

    public String getEmulatorHost() {
        return emulatorHost;
    }

    public void setEmulatorHost(String emulatorHost) {
        this.emulatorHost = emulatorHost;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public void setChannelCount(int channelCount) {
        this.channelCount = channelCount;
    }
}

