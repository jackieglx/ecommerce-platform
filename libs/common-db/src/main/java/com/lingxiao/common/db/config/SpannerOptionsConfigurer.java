package com.lingxiao.common.db.config;

import com.google.cloud.spanner.SpannerOptions;

@FunctionalInterface
public interface SpannerOptionsConfigurer {
    void accept(SpannerOptions.Builder builder);
}

