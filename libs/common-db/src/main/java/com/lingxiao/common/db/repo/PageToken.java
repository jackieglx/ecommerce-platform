package com.lingxiao.common.db.repo;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class PageToken {

    private final String token;

    private PageToken(String token) {
        this.token = token;
    }

    public static PageToken of(String token) {
        return new PageToken(token);
    }

    public static PageToken fromOffset(long offset) {
        String raw = Long.toString(offset);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return new PageToken(encoded);
    }

    public long toOffset(long defaultValue) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            return Long.parseLong(raw);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public String value() {
        return token;
    }
}

