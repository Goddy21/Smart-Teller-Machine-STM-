package com.stm.middleware.journey;

import com.stm.middleware.common.Hashing;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** KYC document archive. Access is limited to the middleware and the portal; every portal read is audited. */
@Repository
public class DocumentRepository {
    public record DocMeta(UUID id, String type, String mimeType, int sizeBytes, String sha256, OffsetDateTime createdAt) {}

    public record DocContent(String mimeType, byte[] content) {}

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public DocMeta insert(UUID sessionId, String type, String mimeType, byte[] content) {
        UUID id = UUID.randomUUID();
        String sha = Hashing.sha256Hex(content);
        jdbc.sql("""
                INSERT INTO kyc_documents (id, session_id, doc_type, mime_type, size_bytes, sha256, content)
                VALUES (:id, :s, :t, :m, :z, :h, :c)""")
                .param("id", id).param("s", sessionId).param("t", type).param("m", mimeType)
                .param("z", content.length).param("h", sha).param("c", content).update();
        return new DocMeta(id, type, mimeType, content.length, sha, OffsetDateTime.now());
    }

    public boolean exists(UUID sessionId, String type) {
        Long n = jdbc.sql("SELECT count(*) FROM kyc_documents WHERE session_id = :s AND doc_type = :t")
                .param("s", sessionId).param("t", type).query(Long.class).single();
        return n != null && n > 0;
    }

    public List<DocMeta> list(UUID sessionId) {
        return jdbc.sql("""
                SELECT id, doc_type, mime_type, size_bytes, sha256, created_at
                FROM kyc_documents WHERE session_id = :s ORDER BY created_at""")
                .param("s", sessionId)
                .query((rs, n) -> new DocMeta(UUID.fromString(rs.getString("id")), rs.getString("doc_type"),
                        rs.getString("mime_type"), rs.getInt("size_bytes"), rs.getString("sha256"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public Optional<DocContent> content(UUID sessionId, UUID docId) {
        return jdbc.sql("SELECT mime_type, content FROM kyc_documents WHERE id = :d AND session_id = :s")
                .param("d", docId).param("s", sessionId)
                .query((rs, n) -> new DocContent(rs.getString("mime_type"), rs.getBytes("content")))
                .optional();
    }

    public void deleteBySession(UUID sessionId) {
        jdbc.sql("DELETE FROM kyc_documents WHERE session_id = :s").param("s", sessionId).update();
    }
}
