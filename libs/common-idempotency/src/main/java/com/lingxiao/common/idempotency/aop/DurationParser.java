package com.lingxiao.common.idempotency.aop;

import java.time.Duration;

public class DurationParser {
    public Duration parse(String text) {
        return Duration.parse(text);
    }
}

