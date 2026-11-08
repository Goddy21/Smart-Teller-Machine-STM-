package com.stm.middleware.stm.onboarding;

import com.stm.middleware.stm.core.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Account products (B-1) and the terms text with its consents (B-2). */
@RestController
@RequestMapping("/stm/v1")
public class ReferenceController {
    private final JdbcClient jdbc;
    private final Json json;

    public ReferenceController(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping("/products")
    @SessionOptional
    public Map<String, Object> products(Ctx ctx, @RequestParam(defaultValue = "STM") String channel,
                                        @RequestParam(defaultValue = "ACCOUNT") String category) {
        boolean sw = ctx.sw();
        List<Map<String, Object>> list = jdbc.sql("""
                SELECT * FROM products WHERE category = :c AND active AND (:ch <> 'STM' OR channel_stm) ORDER BY sort_order""")
                .param("c", category).param("ch", channel)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("name", sw && rs.getString("name_sw") != null ? rs.getString("name_sw") : rs.getString("name"));
                    m.put("minOpeningBalance", Money.of(rs.getBigDecimal("min_opening_balance"), rs.getString("currency")));
                    m.put("features", json.read(sw ? rs.getString("features_sw") : rs.getString("features"), Json.STRINGS));
                    m.put("cardProducts", json.read(rs.getString("card_products"), Json.STRINGS));
                    return m;
                }).list();
        return Map.of("products", list);
    }

    @GetMapping("/legal/terms")
    @SessionOptional
    public Map<String, Object> terms(Ctx ctx, @RequestParam String product, @RequestParam(required = false) String lang) {
        String language = lang != null && lang.matches("en|sw") ? lang : ctx.lang();
        return jdbc.sql("""
                SELECT * FROM legal_terms WHERE language = :l AND active AND (product_code IS NULL OR product_code = :p)
                ORDER BY version DESC LIMIT 1""")
                .param("l", language).param("p", product)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("termsId", rs.getString("terms_id"));
                    m.put("version", rs.getString("version"));
                    m.put("language", rs.getString("language"));
                    m.put("sections", json.read(rs.getString("sections"), Json.LIST_OF_MAPS));
                    m.put("consents", json.read(rs.getString("consents"), Json.LIST_OF_MAPS));
                    m.put("audioUrl", rs.getString("audio_url"));
                    return m;
                }).optional()
                .orElseThrow(() -> Errors.notFound("TERMS_NOT_FOUND", "No terms for this product", "Hakuna masharti ya bidhaa hii"));
    }
}
