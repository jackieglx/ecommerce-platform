package com.lingxiao.catalog.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;

public class CacheValueCodec {
    private final ObjectMapper om;

    public CacheValueCodec(ObjectMapper om) {
        this.om = om;
    }

    public byte[] encode(CacheValue v) {
        try {
            return om.writeValueAsBytes(v);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode CacheValue", e);
        }
    }

    public CacheValue decode(byte[] bytes) {
        if (bytes == null) return null;
        try {
            return om.readValue(bytes, CacheValue.class);
        } catch (Exception e) {
            return null; // 由调用方决定是否 delete key 自愈
        }
    }
}
