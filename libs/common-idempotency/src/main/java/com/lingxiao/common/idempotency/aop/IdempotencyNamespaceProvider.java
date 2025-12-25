package com.lingxiao.common.idempotency.aop;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

public class IdempotencyNamespaceProvider {

    private final Environment env;

    public IdempotencyNamespaceProvider(Environment env) {
        this.env = env;
    }

    public String namespace() {
        String ns = env.getProperty("idempotency.namespace");
        if (StringUtils.hasText(ns)) {
            return sanitize(ns);
        }
        ns = env.getProperty("spring.kafka.consumer.group-id");
        if (StringUtils.hasText(ns)) {
            return sanitize(ns);
        }
        ns = env.getProperty("spring.application.name");
        if (StringUtils.hasText(ns)) {
            return sanitize(ns);
        }
        return "default";
    }

    private String sanitize(String raw) {
        if (raw == null) {
            return "default";
        }
        String trimmed = raw.trim();
        if (!StringUtils.hasText(trimmed)) {
            return "default";
        }
        String cleaned = trimmed.replaceAll("\\s+", "_");
        if (cleaned.length() > 200) {
            cleaned = cleaned.substring(0, 200);
        }
        return cleaned;
    }
}


