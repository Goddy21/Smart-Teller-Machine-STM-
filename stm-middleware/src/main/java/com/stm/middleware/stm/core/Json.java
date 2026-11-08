package com.stm.middleware.stm.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** JSON columns (TEXT) in and out. */
@Component
public class Json {
    public static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    public static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {};
    public static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object o) {
        if (o == null) return null;
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise JSON", e);
        }
    }

    public <T> T read(String s, TypeReference<T> type) {
        if (s == null) return null;
        try {
            return mapper.readValue(s, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt JSON column", e);
        }
    }

    public JsonNode tree(String s) {
        if (s == null) return null;
        try {
            return mapper.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt JSON", e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
