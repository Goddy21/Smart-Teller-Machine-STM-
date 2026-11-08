package com.stm.middleware.portal;

import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Read-only SQL for the portal: rows come back as JSON-ready maps with camelCase keys and ISO timestamps.
 * Columns ending in _json are parsed as JSON.
 */
@Component
public class Rows {
    private final JdbcClient jdbc;
    private final com.stm.middleware.stm.core.Json json;

    public Rows(JdbcClient jdbc, com.stm.middleware.stm.core.Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Map<String, Object>> list(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query(new ColumnMapRowMapper()).list().stream().map(this::convert).toList();
    }

    public List<Map<String, Object>> list(String sql) {
        return list(sql, Map.of());
    }

    public Optional<Map<String, Object>> one(String sql, Map<String, ?> params) {
        List<Map<String, Object>> l = list(sql, params);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    private Map<String, Object> convert(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            String key = k;
            Object value = v;
            if (k.endsWith("_json")) {
                key = k.substring(0, k.length() - 5);
                value = v == null ? null : json.tree(v.toString());
            } else if (v instanceof Timestamp t) {
                value = t.toInstant().atOffset(ZoneOffset.UTC);
            }
            out.put(camel(key), value);
        });
        return out;
    }

    static String camel(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return b.toString();
    }
}
