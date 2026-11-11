package com.stm.middleware.portal;

import com.stm.middleware.audit.AuditService;
import com.stm.middleware.auth.PortalUser;
import com.stm.middleware.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Customer sessions and account-opening applications for the portal, with the teller's decisions on applications
 * that need a person: a screening referral to clear, an identity a teller confirmed on video, or a rejection.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalSessionsController {
    private final Rows rows;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final com.stm.middleware.backend.CoreBanking core;

    public PortalSessionsController(Rows rows, JdbcClient jdbc, AuditService audit, com.stm.middleware.backend.CoreBanking core) {
        this.rows = rows;
        this.jdbc = jdbc;
        this.audit = audit;
        this.core = core;
    }

    private static final String SESSION_COLUMNS = """
            s.id, t.terminal_code, s.start_trigger, s.language, s.status, s.flow, s.customer_id, s.customer_name,
            s.started_at, s.last_activity_at, s.ended_at, s.end_reason, s.card_retained,
            (SELECT count(*) FROM applications a WHERE a.session_id = s.id) AS applications_count,
            (SELECT a.status FROM applications a WHERE a.session_id = s.id ORDER BY a.created_at DESC LIMIT 1) AS application_status,
            (SELECT count(*) FROM card_orders c WHERE c.session_id = s.id) AS card_orders_count,
            (SELECT count(*) FROM cash_deposits d WHERE d.session_id = s.id) AS deposits_count,
            (SELECT count(*) FROM cash_withdrawals w WHERE w.session_id = s.id) AS withdrawals_count,
            (SELECT count(*) FROM fx_exchanges x WHERE x.session_id = s.id) AS fx_count,
            (SELECT count(*) FROM statements m WHERE m.session_id = s.id) AS statements_count,
            (SELECT count(*) FROM teller_calls c WHERE c.session_id = s.id) AS teller_calls_count,
            (SELECT count(*) FROM service_events e WHERE e.session_id = s.id) AS service_events_count,
            EXISTS (SELECT 1 FROM applications a WHERE a.session_id = s.id
                    AND (a.status = 'REFERRED' OR (a.status = 'IDENTITY_CAPTURED' AND a.biometric_attempts >= 3))) AS needs_teller
            FROM stm_sessions s JOIN terminals t ON t.id = s.terminal_id""";

    private static final String SESSIONS_FROM = " FROM stm_sessions s JOIN terminals t ON t.id = s.terminal_id";
    private static final String NEEDS_TELLER = """
            EXISTS (SELECT 1 FROM applications a WHERE a.session_id = s.id
                    AND (a.status = 'REFERRED' OR (a.status = 'IDENTITY_CAPTURED' AND a.biometric_attempts >= 3)))""";

    /**
     * Newest first. view = teller | active | ended | all. q searches name, CIF, kiosk and session id across all sessions.
     * With ?page= (from 1) and &size= the answer is a page {items, total, page, size, pages, counts}; without, a plain list.
     */
    @GetMapping("/sessions")
    public Object sessions(@RequestParam(required = false) String status,
                           @RequestParam(required = false) String flow,
                           @RequestParam(required = false) String q,
                           @RequestParam(required = false) String view,
                           @RequestParam(required = false) Integer page,
                           @RequestParam(defaultValue = "50") int size,
                           @RequestParam(defaultValue = "200") int limit) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> p = new HashMap<>();
        if (status != null && !status.isBlank()) { where.append(" AND s.status = :st"); p.put("st", status); }
        if (flow != null && !flow.isBlank()) { where.append(" AND s.flow = :f"); p.put("f", flow); }
        if (q != null && !q.isBlank()) {
            where.append(" AND (s.id ILIKE :q OR s.customer_name ILIKE :q OR s.customer_id ILIKE :q OR t.terminal_code ILIKE :q)");
            p.put("q", "%" + q.trim() + "%");
        }
        String base = where.toString();
        String viewSql = switch (view == null ? "all" : view) {
            case "teller" -> " AND " + NEEDS_TELLER;
            case "active" -> " AND s.status = 'ACTIVE'";
            case "ended" -> " AND s.status <> 'ACTIVE'";
            default -> "";
        };
        String select = "SELECT " + SESSION_COLUMNS + base + viewSql + " ORDER BY s.started_at DESC, s.id DESC";
        if (page == null) {
            p.put("l", Math.max(1, Math.min(limit, 1000)));
            return rows.list(select + " LIMIT :l", p);
        }
        Rows.Page pg = rows.page("SELECT count(*)" + SESSIONS_FROM + base + viewSql, select, p, page, size);
        Map<String, Object> c = rows.one("""
                SELECT count(*) FILTER (WHERE %s) AS teller, count(*) FILTER (WHERE s.status = 'ACTIVE') AS active,
                       count(*) FILTER (WHERE s.status <> 'ACTIVE') AS ended, count(*) AS all_sessions""".formatted(NEEDS_TELLER)
                + SESSIONS_FROM + base, p).orElseThrow();
        return pg.withCounts(Map.of("teller", num(c.get("teller")), "active", num(c.get("active")),
                "ended", num(c.get("ended")), "all", num(c.get("allSessions"))));
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }

    @GetMapping("/sessions/{id}")
    public Map<String, Object> session(@PathVariable String id) {
        Map<String, Object> s = rows.one("SELECT " + SESSION_COLUMNS + " WHERE s.id = :id", Map.of("id", id))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session not found"));
        Map<String, Object> p = Map.of("s", id);
        List<Map<String, Object>> apps = rows.list("SELECT id FROM applications WHERE session_id = :s ORDER BY created_at", p)
                .stream().map(a -> application(a.get("id").toString())).toList();
        s.put("applications", apps);
        s.put("verifications", rows.list("""
                SELECT id, application_id, purpose, document_type, country, result, document_checks AS document_checks_json,
                       registry AS registry_json, registry_source, existing_customer, existing_customer_id, created_at
                FROM identity_verifications WHERE session_id = :s ORDER BY created_at""", p));
        s.put("biometrics", rows.list("""
                SELECT id, application_id, customer_id, face_result, face_score, liveness_score, finger, finger_result,
                       finger_score, finger_quality, overall, attempts_left, created_at
                FROM biometric_checks WHERE session_id = :s ORDER BY created_at""", p));
        s.put("cardOrders", rows.list("""
                SELECT id, service, account_id, card_product, name_on_card, masked_pan, card_id, fee_amount, fee_posted, fee_ref,
                       status, outcome, steps AS steps_json, replaces_card_id, reference, created_at, updated_at
                FROM card_orders WHERE session_id = :s ORDER BY created_at""", p));
        s.put("deposits", rows.list("""
                SELECT id, target_label, currency, status, total, fee, accepted AS accepted_json, rejected_count,
                       suspected_counterfeit, transaction_ref, core_ref, new_balance, cancel_reason, created_at, posted_at
                FROM cash_deposits WHERE session_id = :s ORDER BY created_at""", p));
        s.put("withdrawals", rows.list("""
                SELECT id, source, account_id, currency, amount, local_amount, mix AS mix_json, status, transaction_ref,
                       core_ref, reversal_ref, reverse_reason, credit_back_by, created_at, updated_at
                FROM cash_withdrawals WHERE session_id = :s ORDER BY created_at""", p));
        s.put("fxExchanges", rows.list("""
                SELECT x.id, x.currency, x.rate, x.destination_type, x.account_id, x.status, x.foreign_total, x.local_amount,
                       x.transaction_ref, x.serials AS serials_json, x.created_at
                FROM fx_exchanges x WHERE x.session_id = :s ORDER BY x.created_at""", p));
        // PIN changes, card freezes, reactivations, phone-number changes, enrolments and signatures in this session.
        s.put("serviceEvents", rows.list("""
                SELECT id, kind, subject, detail AS detail_json, reference, created_at
                FROM service_events WHERE session_id = :s ORDER BY created_at""", p));
        if (s.get("customerId") != null) s.put("customerRecord", customerRecord(s.get("customerId").toString()));
        s.put("statements", rows.list("""
                SELECT id, account_id, type, period_months, fee, fee_ref, pages, reference, created_at
                FROM statements WHERE session_id = :s ORDER BY created_at""", p));
        s.put("receipts", rows.list("""
                SELECT id, transaction_ref, delivery, print_status, lines AS lines_json, created_at
                FROM receipts WHERE session_id = :s ORDER BY created_at""", p));
        s.put("sms", rows.list("""
                SELECT id, template, body, status, created_at FROM sms_messages WHERE session_id = :s ORDER BY created_at""", p));
        s.put("tellerCalls", rows.list("""
                SELECT id, screen, flow, application_id, language, status, created_at, updated_at
                FROM teller_calls WHERE session_id = :s ORDER BY created_at""", p));
        return s;
    }

    /** One session's audit entries, oldest first. Open to every portal role (the full journal is for audit roles). */
    @GetMapping("/sessions/{id}/timeline")
    public List<Map<String, Object>> timeline(@PathVariable String id) {
        return rows.list("""
                SELECT a.seq, a.event_type, a.entity_id, t.terminal_code, a.payload AS payload_json, a.hash, a.created_at
                FROM audit_journal a LEFT JOIN terminals t ON t.id = a.terminal_id
                WHERE a.entity_id = :e ORDER BY a.seq LIMIT 300""", Map.of("e", id));
    }

    // ------------------------------------------------------------------ captures (audited)

    /** What core banking holds to recognise the customer: fingerprints, specimen signature, phone-number history. */
    private Map<String, Object> customerRecord(String customerId) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            var bio = core.biometrics(customerId);
            var sig = core.signature(customerId);
            out.put("biometricsEnrolled", bio.enrolled());
            out.put("fingers", bio.fingers());
            out.put("biometricsSource", bio.source());
            out.put("biometricsEnrolledAt", bio.enrolledAt());
            out.put("signatureOnFile", sig.isPresent());
            out.put("signatureSource", sig.map(com.stm.middleware.backend.CoreBanking.Signature::source).orElse(null));
            out.put("signatureCapturedAt", sig.map(com.stm.middleware.backend.CoreBanking.Signature::capturedAt).orElse(null));
            out.put("mobileChangedAt", core.mobileChangedAt(customerId).orElse(null));
        } catch (RuntimeException e) {
            out.put("unavailable", true);      // core banking is down: the session still opens
        }
        return out;
    }

    /** The specimen signature core banking holds for a customer. Every view is recorded under the signed-in user. */
    @GetMapping("/customers/{customerId}/signature")
    public ResponseEntity<byte[]> signature(@AuthenticationPrincipal PortalUser user, @PathVariable String customerId) {
        var img = core.signatureImage(customerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SIGNATURE_NOT_FOUND", "No specimen signature on file"));
        audit.record("DOCUMENT_VIEWED", "customer:" + customerId, null, Map.of("kind", "SPECIMEN_SIGNATURE",
                "by", user == null ? "portal-key" : user.username()));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(img.mimeType())).header("Cache-Control", "no-store").body(img.content());
    }

    /**
     * Card and account changes customers made at the kiosks, newest first: PIN changes and resets, card freezes,
     * reactivations, phone-number changes, enrolments, signatures. kind filters; q searches customer, subject, reference.
     */
    @GetMapping("/service-events")
    public Object serviceEvents(@RequestParam(required = false) String kind, @RequestParam(required = false) String q,
                                @RequestParam(required = false) Integer page, @RequestParam(defaultValue = "50") int size) {
        Map<String, Object> p = new HashMap<>();
        p.put("k", kind == null || kind.isBlank() ? null : kind);
        p.put("q", q == null || q.isBlank() ? null : "%" + q.trim() + "%");
        String from = " FROM service_events e JOIN terminals t ON t.id = e.terminal_id JOIN stm_sessions s ON s.id = e.session_id"
                + " WHERE (CAST(:k AS VARCHAR) IS NULL OR e.kind = :k)"
                + " AND (CAST(:q AS VARCHAR) IS NULL OR s.customer_name ILIKE :q OR e.customer_id ILIKE :q OR e.subject ILIKE :q"
                + " OR e.reference ILIKE :q OR t.terminal_code ILIKE :q)";
        String select = "SELECT e.id, e.session_id, t.terminal_code, e.customer_id, s.customer_name, e.kind, e.subject,"
                + " e.detail AS detail_json, e.reference, e.created_at" + from + " ORDER BY e.created_at DESC, e.id DESC";
        if (page == null) return rows.list(select + " LIMIT 200", p);
        return rows.page("SELECT count(*)" + from, select, p, page, size);
    }

    /** An ID image, face, registry photo or signature. Every view is recorded under the signed-in user. */
    @GetMapping("/captures/{id}")
    public ResponseEntity<byte[]> capture(@AuthenticationPrincipal PortalUser user, @PathVariable UUID id) {
        record C(String owner, String kind, String mime, byte[] content) {}
        C c = jdbc.sql("SELECT owner_id, kind, mime_type, content FROM captures WHERE id = :id").param("id", id)
                .query((rs, n) -> new C(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBytes(4))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CAPTURE_NOT_FOUND", "Image not found"));
        audit.record("DOCUMENT_VIEWED", c.owner(), null, Map.of("captureId", id.toString(), "kind", c.kind(),
                "by", user == null ? "portal-key" : user.username()));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(c.mime())).header("Cache-Control", "no-store").body(c.content());
    }

    // ------------------------------------------------------------------ applications

    /**
     * Most recently changed first. view = teller (a kiosk is waiting) | open (in progress) | opened | all.
     * q searches name, account number, application id and kiosk. ?page= gives a page, as for /sessions.
     */
    @GetMapping("/applications")
    public Object applications(@RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "false") boolean needsTeller,
                               @RequestParam(required = false) String view,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(defaultValue = "50") int size,
                               @RequestParam(defaultValue = "200") int limit) {
        StringBuilder where = new StringBuilder(" FROM applications a JOIN terminals t ON t.id = a.terminal_id WHERE 1 = 1");
        Map<String, Object> p = new HashMap<>();
        if (status != null && !status.isBlank()) { where.append(" AND a.status = :st"); p.put("st", status); }
        String v = needsTeller ? "teller" : view == null ? "all" : view;
        switch (v) {
            case "teller" -> where.append(" AND (a.status = 'REFERRED' OR (a.status = 'IDENTITY_CAPTURED' AND a.biometric_attempts >= 3)) AND a.expires_at > now()");
            case "open" -> where.append(" AND a.status NOT IN ('ACCOUNT_OPENED', 'CANCELLED', 'REJECTED', 'EXPIRED')");
            case "opened" -> where.append(" AND a.status = 'ACCOUNT_OPENED'");
            default -> { }
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (a.full_name ILIKE :q OR a.account_number ILIKE :q OR a.id ILIKE :q OR t.terminal_code ILIKE :q OR a.customer_id ILIKE :q)");
            p.put("q", "%" + q.trim() + "%");
        }
        String select = """
                SELECT a.id, a.session_id, t.terminal_code, a.product_code, a.status, a.full_name, a.referral_status,
                       a.teller_override, a.biometric_attempts, a.identity_attempts, a.account_number, a.customer_id,
                       a.created_at, a.updated_at, a.expires_at""" + where + " ORDER BY a.updated_at DESC, a.id DESC";
        if (page == null) {
            p.put("l", Math.max(1, Math.min(limit, 1000)));
            return rows.list(select + " LIMIT :l", p);
        }
        return rows.page("SELECT count(*) " + where, select, p, page, size);
    }

    @GetMapping("/applications/{id}")
    public Map<String, Object> application(@PathVariable String id) {
        Map<String, Object> a = rows.one("""
                SELECT a.id, a.session_id, t.terminal_code, a.product_code, p.name AS product_name, a.terms_id,
                       a.consents AS consents_json, a.accepted_at, a.status, a.verification_id, a.identity_attempts,
                       a.biometrics_passed, a.biometric_attempts, a.identity_confirmed, a.full_name, a.id_number, a.kra_pin,
                       a.mobile, a.address AS address_json, a.occupation, a.source_of_funds, a.screening AS screening_json,
                       a.otp_verified, a.signature_id, a.customer_id, a.account_id, a.account_number, a.card_product,
                       a.name_on_card, a.reference, a.referral_status, a.teller_override, a.decided_by, a.decided_at,
                       a.decision_notes, a.cancel_reason, a.cancelled_at_step, a.created_at, a.updated_at, a.expires_at
                FROM applications a JOIN terminals t ON t.id = a.terminal_id JOIN products p ON p.code = a.product_code
                WHERE a.id = :id""", Map.of("id", id))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Application not found"));
        if (a.get("idNumber") instanceof String n && n.length() > 3) {
            a.put("idNumberMasked", "•".repeat(n.length() - 3) + n.substring(n.length() - 3));
        }
        a.remove("idNumber");
        a.put("registry", rows.one("SELECT registry AS r_json FROM identity_verifications WHERE id = :v",
                Map.of("v", String.valueOf(a.get("verificationId")))).map(m -> m.get("r")).orElse(null));
        a.put("captures", rows.list("""
                SELECT c.id, c.kind, c.owner_type, c.mime_type, c.size_bytes, c.created_at FROM captures c
                WHERE c.owner_id = :a
                   OR c.owner_id IN (SELECT id FROM identity_verifications WHERE application_id = :a)
                   OR c.owner_id IN (SELECT id FROM biometric_checks WHERE application_id = :a)
                ORDER BY c.created_at""", Map.of("a", id)));
        a.put("needsTeller", "REFERRED".equals(a.get("status"))
                || ("IDENTITY_CAPTURED".equals(a.get("status")) && ((Number) a.get("biometricAttempts")).intValue() >= 3));
        return a;
    }

    public record DecisionRequest(@NotBlank @Pattern(regexp = "CLEAR_REFERRAL|VERIFY_IDENTITY|REJECT") String decision,
                                  @Size(max = 500) String notes) {}

    /**
     * CLEAR_REFERRAL: a screening hit was reviewed and cleared; the kiosk continues with the OTP (B-9).
     * VERIFY_IDENTITY: biometrics failed but the teller confirmed the person against the ID on video; the kiosk
     * continues with the review screen (B-6). REJECT: the application stops.
     */
    @PostMapping("/applications/{id}/decision")
    @Transactional
    public Map<String, Object> decide(@AuthenticationPrincipal PortalUser user, @PathVariable String id,
                                      @Valid @RequestBody DecisionRequest req) {
        String actor = user == null ? "portal-key" : user.username();
        record A(String status, String referral, int bioAttempts, String session, long terminal) {}
        A a = jdbc.sql("""
                SELECT status, referral_status, biometric_attempts, session_id, terminal_id FROM applications
                WHERE id = :id FOR UPDATE""")
                .param("id", id).query((rs, n) -> new A(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getLong(5))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Application not found"));
        String newStatus;
        String referral = a.referral();
        String override = null;
        switch (req.decision()) {
            case "CLEAR_REFERRAL" -> {
                if (!"REFERRED".equals(a.status())) throw conflict("Only a referred application can be cleared");
                newStatus = "KYC_CAPTURED";
                referral = "CLEARED";
            }
            case "VERIFY_IDENTITY" -> {
                if (!"IDENTITY_CAPTURED".equals(a.status())) throw conflict("Identity can be verified by a teller only after the ID scan");
                newStatus = "BIOMETRICS_VERIFIED";
                override = "IDENTITY";
            }
            default -> {
                if (Set.of("ACCOUNT_OPENED", "CANCELLED", "REJECTED", "EXPIRED").contains(a.status())) {
                    throw conflict("This application is already " + a.status().toLowerCase());
                }
                newStatus = "REJECTED";
                if ("REFERRED".equals(a.status())) referral = "REJECTED";
            }
        }
        jdbc.sql("""
                UPDATE applications SET status = :st, referral_status = :rf, teller_override = coalesce(:ov, teller_override),
                       biometrics_passed = biometrics_passed OR :bio, decided_by = :by, decided_at = now(), decision_notes = :n,
                       expires_at = greatest(expires_at, now() + interval '15 minutes'), updated_at = now()
                WHERE id = :id""")
                .param("st", newStatus).param("rf", referral).param("ov", override).param("bio", override != null)
                .param("by", actor).param("n", req.notes()).param("id", id).update();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", id);
        payload.put("decision", req.decision());
        payload.put("by", actor);
        payload.put("newStatus", newStatus);
        if (req.notes() != null) payload.put("notes", req.notes());
        audit.record("TELLER_DECISION", a.session(), a.terminal(), payload);
        return application(id);
    }

    private static ApiException conflict(String msg) {
        return new ApiException(HttpStatus.CONFLICT, "WRONG_STATE", msg);
    }
}
