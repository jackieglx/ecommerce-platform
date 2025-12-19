package com.lingxiao.common.db.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxiao.common.db.errors.DbException;

public class JsonMapper {

    private final ObjectMapper objectMapper;

    public JsonMapper() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public JsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new DbException("Failed to serialize to json", e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new DbException("Failed to deserialize json", e);
        }
    }
}

