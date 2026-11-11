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

    /**
     * One page of a list. {@code countSql} must return a single count for the same filters; {@code selectSql} must
     * already end with its ORDER BY (a unique tie-breaker keeps pages stable). Pages are numbered from 1.
     */
    public Page page(String countSql, String selectSql, Map<String, ?> params, int page, int size) {
        int sz = Math.max(1, Math.min(size, 200));
        long total = jdbc.sql(countSql).params(params).query(Long.class).single();
        int pages = (int) Math.max(1, (total + sz - 1) / sz);
        int pg = Math.max(1, Math.min(page, pages));
        Map<String, Object> p = new HashMap<>(params);
        p.put("pageLimit", sz);
        p.put("pageOffset", (long) (pg - 1) * sz);
        List<Map<String, Object>> items = list(selectSql + " LIMIT :pageLimit OFFSET :pageOffset", p);
        return new Page(items, total, pg, sz, pages, null);
    }

    /** A page of rows. counts: optional totals per filter tab (for example on the sessions list). */
    public record Page(List<Map<String, Object>> items, long total, int page, int size, int pages, Map<String, Long> counts) {
        public Page withCounts(Map<String, Long> c) { return new Page(items, total, page, size, pages, c); }
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
