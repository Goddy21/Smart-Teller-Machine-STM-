package com.stm.middleware.stm.support;

import com.stm.middleware.common.Hashing;
import com.stm.middleware.stm.core.Errors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Stores images from the device agent (ID front/back/UV, face, signature) and the registry photo. */
@Service
public class Captures {
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    private final JdbcClient jdbc;

    public Captures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Capture(String mimeType, byte[] content) {}

    /** Decodes base64 (with or without a data: prefix). Empty/null -> null. */
    public static byte[] decode(String field, String base64) {
        if (base64 == null || base64.isBlank() || base64.startsWith("<")) return null;   // "<base64 JPEG>" placeholders
        String b = base64.contains(",") && base64.startsWith("data:") ? base64.substring(base64.indexOf(',') + 1) : base64;
        try {
            byte[] raw = Base64.getMimeDecoder().decode(b);
            if (raw.length > MAX_BYTES) throw Errors.validation(field, field + " is larger than 4 MB");
            return raw;
        } catch (IllegalArgumentException e) {
            throw Errors.validation(field, field + " is not valid base64");
        }
    }

    public static String mimeOf(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        return "application/octet-stream";
    }

    public void store(String ownerType, String ownerId, String kind, byte[] content) {
        if (content == null || content.length == 0) return;
        jdbc.sql("""
                INSERT INTO captures (id, owner_type, owner_id, kind, mime_type, size_bytes, sha256, content)
                VALUES (:id, :ot, :o, :k, :m, :s, :h, :c)""")
                .param("id", UUID.randomUUID()).param("ot", ownerType).param("o", ownerId).param("k", kind)
                .param("m", mimeOf(content)).param("s", content.length).param("h", Hashing.sha256Hex(content))
                .param("c", content).update();
    }

    public Optional<Capture> latest(String ownerId, String kind) {
        return jdbc.sql("""
                SELECT mime_type, content FROM captures WHERE owner_id = :o AND kind = :k ORDER BY created_at DESC LIMIT 1""")
                .param("o", ownerId).param("k", kind)
                .query((rs, n) -> new Capture(rs.getString(1), rs.getBytes(2))).optional();
    }
}
