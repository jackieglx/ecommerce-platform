package com.lingxiao.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "catalog.cache")
public class CatalogCacheProperties {
    // L1
    private Duration l1Ttl = Duration.ofSeconds(30);
    private Duration l1NegativeTtl = Duration.ofSeconds(10);
    private long l1MaxSize = 10000;

    // L2
    private Duration l2Ttl = Duration.ofMinutes(5);
    private Duration l2NegativeTtl = Duration.ofSeconds(60);
    private long l2JitterMs = 500;

    // SingleFlight
    private Duration singleFlightTimeout = Duration.ofSeconds(2);

    // Pub/Sub
    private String invalidationChannel = "catalog:cache:invalidate";
    private String skuPrefix = "catalog:sku:";

    public Duration getL1Ttl() {
        return l1Ttl;
    }

    public void setL1Ttl(Duration l1Ttl) {
        this.l1Ttl = l1Ttl;
    }

    public Duration getL1NegativeTtl() {
        return l1NegativeTtl;
    }

    public void setL1NegativeTtl(Duration l1NegativeTtl) {
        this.l1NegativeTtl = l1NegativeTtl;
    }

    public long getL1MaxSize() {
        return l1MaxSize;
    }

    public void setL1MaxSize(long l1MaxSize) {
        this.l1MaxSize = l1MaxSize;
    }

    public Duration getL2Ttl() {
        return l2Ttl;
    }

    public void setL2Ttl(Duration l2Ttl) {
        this.l2Ttl = l2Ttl;
    }

    public Duration getL2NegativeTtl() {
        return l2NegativeTtl;
    }

    public void setL2NegativeTtl(Duration l2NegativeTtl) {
        this.l2NegativeTtl = l2NegativeTtl;
    }

    public long getL2JitterMs() {
        return l2JitterMs;
    }

    public void setL2JitterMs(long l2JitterMs) {
        this.l2JitterMs = l2JitterMs;
    }

    public Duration getSingleFlightTimeout() {
        return singleFlightTimeout;
    }

    public void setSingleFlightTimeout(Duration singleFlightTimeout) {
        this.singleFlightTimeout = singleFlightTimeout;
    }

    public String getInvalidationChannel() {
        return invalidationChannel;
    }

    public void setInvalidationChannel(String invalidationChannel) {
        this.invalidationChannel = invalidationChannel;
    }

    public String getSkuPrefix() {
        return skuPrefix;
    }

    public void setSkuPrefix(String skuPrefix) {
        this.skuPrefix = skuPrefix;
    }
}


