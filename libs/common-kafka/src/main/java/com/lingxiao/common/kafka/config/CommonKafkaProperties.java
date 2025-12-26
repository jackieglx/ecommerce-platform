package com.lingxiao.common.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "common.kafka")
public class CommonKafkaProperties {

    private final Consumer consumer = new Consumer();
    private final Retry retry = new Retry();
    private final Dlt dlt = new Dlt();
    private final Logging logging = new Logging();

    public Consumer getConsumer() {
        return consumer;
    }

    public Retry getRetry() {
        return retry;
    }

    public Dlt getDlt() {
        return dlt;
    }

    public Logging getLogging() {
        return logging;
    }

    public static class Consumer {
        private boolean enabled = true;
        private String ackMode = "RECORD";
        private Integer concurrency = 1;
        private Long pollTimeoutMs = 1500L;
        private Integer maxPollRecords;
        private Boolean missingTopicsFatal = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAckMode() {
            return ackMode;
        }

        public void setAckMode(String ackMode) {
            this.ackMode = ackMode;
        }

        public Integer getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(Integer concurrency) {
            this.concurrency = concurrency;
        }

        public Long getPollTimeoutMs() {
            return pollTimeoutMs;
        }

        public void setPollTimeoutMs(Long pollTimeoutMs) {
            this.pollTimeoutMs = pollTimeoutMs;
        }

        public Integer getMaxPollRecords() {
            return maxPollRecords;
        }

        public void setMaxPollRecords(Integer maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
        }

        public Boolean getMissingTopicsFatal() {
            return missingTopicsFatal;
        }

        public void setMissingTopicsFatal(Boolean missingTopicsFatal) {
            this.missingTopicsFatal = missingTopicsFatal;
        }
    }

    public static class Retry {
        private boolean enabled = true;
        private long initialBackoffMs = 200L;
        private double multiplier = 2.0;
        private long maxBackoffMs = 30_000L;
        private int maxAttempts = 6;
        private long inProgressInitialBackoffMs = 200L;
        private long inProgressMaxBackoffMs = 2_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getInProgressInitialBackoffMs() {
            return inProgressInitialBackoffMs;
        }

        public void setInProgressInitialBackoffMs(long inProgressInitialBackoffMs) {
            this.inProgressInitialBackoffMs = inProgressInitialBackoffMs;
        }

        public long getInProgressMaxBackoffMs() {
            return inProgressMaxBackoffMs;
        }

        public void setInProgressMaxBackoffMs(long inProgressMaxBackoffMs) {
            this.inProgressMaxBackoffMs = inProgressMaxBackoffMs;
        }
    }

    public static class Dlt {
        private boolean enabled = true;
        private String suffix = ".DLT";
        private boolean samePartition = true;
        private boolean includeStacktrace = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSuffix() {
            return suffix;
        }

        public void setSuffix(String suffix) {
            this.suffix = suffix;
        }

        public boolean isSamePartition() {
            return samePartition;
        }

        public void setSamePartition(boolean samePartition) {
            this.samePartition = samePartition;
        }

        public boolean isIncludeStacktrace() {
            return includeStacktrace;
        }

        public void setIncludeStacktrace(boolean includeStacktrace) {
            this.includeStacktrace = includeStacktrace;
        }
    }

    public static class Logging {
        private boolean mdcEnabled = true;

        public boolean isMdcEnabled() {
            return mdcEnabled;
        }

        public void setMdcEnabled(boolean mdcEnabled) {
            this.mdcEnabled = mdcEnabled;
        }
    }
}


