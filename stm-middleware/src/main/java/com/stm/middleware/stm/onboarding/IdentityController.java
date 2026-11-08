package com.stm.middleware.stm.onboarding;

import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.backend.IdentityServices;
import com.stm.middleware.backend.IdentityServices.RegistryRecord;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.support.Captures;
import com.stm.middleware.stm.support.Mrz;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Identity: document scan + registry check (B-4) and face liveness + thumbprint (B-5).
 * B-4 serves account opening (purpose ONBOARDING, with applicationId) and forex (purpose FOREX).
 * B-5 serves account opening (applicationId) and card services (customerId + lookupRef, after the OTP):
 * there a match returns the customerToken that unlocks C-2 .. C-10.
 */
@RestController
@RequestMapping("/stm/v1/identity")
public class IdentityController {
    static final int MAX_IDENTITY_ATTEMPTS = 2;   // spec: teller offer after 2 tries
    static final int MAX_BIOMETRIC_ATTEMPTS = 3;

    private final IdentityServices identity;
    private final CoreBanking core;
    private final Applications applications;
    private final Captures captures;
    private final CustomerTokens customerTokens;
    private final SessionStore sessions;
    private final JdbcClient jdbc;
    private final Json json;
    private final StmAudit audit;

    public IdentityController(IdentityServices identity, CoreBanking core, Applications applications, Captures captures,
                              CustomerTokens customerTokens, SessionStore sessions, JdbcClient jdbc, Json json,
                              StmAudit audit) {
        this.identity = identity;
        this.core = core;
        this.applications = applications;
        this.captures = captures;
        this.customerTokens = customerTokens;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ B-4

    public record Ocr(@Size(max = 30) String documentNumber, @Size(max = 30) String serialNumber,
                      @Size(max = 60) String surname, @Size(max = 80) String givenNames,
                      @Size(max = 10) String dateOfBirth, @Size(max = 10) String expiryDate) {}

    public record MrzLines(@Size(max = 44) String line1, @Size(max = 44) String line2) {}

    public record Images(String front, String back, String uv) {}

    public record DocumentRequest(@Size(max = 40) String applicationId,
                                  @Pattern(regexp = "ONBOARDING|FOREX") String purpose,
                                  @NotBlank @Pattern(regexp = "NATIONAL_ID|PASSPORT") String documentType,
                                  @Size(max = 3) String country, @Valid Ocr ocr, @Valid MrzLines mrz,
                                  @Valid Images images) {}

    @PostMapping("/documents/verify")
    @Transactional(noRollbackFor = StmException.class)
    public Map<String, Object> verifyDocument(Ctx ctx, @Valid @RequestBody DocumentRequest req) {
        String purpose = req.purpose() != null ? req.purpose() : req.applicationId() != null ? "ONBOARDING" : "FOREX";
        Application app = null;
        if ("ONBOARDING".equals(purpose)) {
            if (req.applicationId() == null) throw Errors.validation("applicationId", "applicationId is required for account opening");
            app = applications.expect(applications.mine(ctx, req.applicationId()),
                    "CONSENTED", "IDENTITY_CAPTURED", "BIOMETRICS_VERIFIED", "IDENTITY_VERIFIED");
        }
        Doc doc = read(req);
        String country = req.country() == null ? (req.documentType().equals("NATIONAL_ID") ? "KEN" : doc.country) : iso3(req.country());

        Optional<RegistryRecord> found = identity.lookup(req.documentType(), country, doc.number);
        String result;
        IdentityServices.DocumentChecks checks = new IdentityServices.DocumentChecks("PASS", "PASS");
        if (found.isEmpty()) {
            result = "NOT_FOUND";
        } else {
            RegistryRecord r = found.get();
            checks = identity.checkDocument(r, doc.expiry, Captures.decode("images.front", req.images() == null ? null : req.images().front()),
                    Captures.decode("images.uv", req.images() == null ? null : req.images().uv()));
            boolean same = (doc.surname == null || norm(doc.surname).equals(norm(r.surname())))
                    && (doc.dob == null || doc.dob.equals(r.dateOfBirth()));
            result = same && checks.pass() ? "MATCH" : "MISMATCH";
        }

        String verificationId = Ids.next("idv");
        boolean existing = false;
        String existingCif = null;
        if (found.isPresent() && "NATIONAL_ID".equals(req.documentType())) {
            Optional<CoreBanking.Customer> c = core.customerByIdNumber(found.get().documentNumber());
            existing = c.isPresent();
            existingCif = c.map(CoreBanking.Customer::id).orElse(null);
        }
        Map<String, Object> registry = found.map(this::registryView).orElse(null);
        jdbc.sql("""
                INSERT INTO identity_verifications (id, session_id, application_id, purpose, document_type, country,
                    document_number, serial_number, ocr, mrz, result, document_checks, registry, registry_source,
                    existing_customer, existing_customer_id)
                VALUES (:id, :s, :a, :p, :dt, :c, :n, :sn, :ocr, :mrz, :r, :chk, :reg, :src, :ex, :exid)""")
                .param("id", verificationId).param("s", ctx.sessionId()).param("a", req.applicationId())
                .param("p", purpose).param("dt", req.documentType()).param("c", country).param("n", doc.number)
                .param("sn", doc.serial).param("ocr", json.write(req.ocr())).param("mrz", json.write(req.mrz()))
                .param("r", result).param("chk", json.write(checks)).param("reg", json.write(registry))
                .param("src", found.map(RegistryRecord::source).orElse(null)).param("ex", existing).param("exid", existingCif)
                .update();
        if (req.images() != null) {
            captures.store("VERIFICATION", verificationId, "FRONT", Captures.decode("images.front", req.images().front()));
            captures.store("VERIFICATION", verificationId, "BACK", Captures.decode("images.back", req.images().back()));
            captures.store("VERIFICATION", verificationId, "UV", Captures.decode("images.uv", req.images().uv()));
        }
        byte[] photo = found.map(identity::registryPhoto).orElse(null);
        captures.store("VERIFICATION", verificationId, "REGISTRY_PHOTO", photo);
        sessions.setFlow(ctx.sessionId(), "ONBOARDING".equals(purpose) ? "ACCOUNT_OPENING" : "FOREX");
        audit.session(ctx, "IDENTITY_DOCUMENT_CHECKED", "verificationId", verificationId, "applicationId", req.applicationId(),
                "purpose", purpose, "documentType", req.documentType(), "result", result, "checks", checks,
                "existingCustomer", existing);

        if (!"MATCH".equals(result)) {
            int left = 0;
            if (app != null) {
                int attempts = app.identityAttempts() + 1;
                applications.update(app.id(), Map.of("identity_attempts", attempts));
                left = Math.max(0, MAX_IDENTITY_ATTEMPTS - attempts);
            }
            throw Errors.identityMismatch(result, left).detail("verificationId", verificationId)
                    .detail("documentChecks", checks).detail("offerTeller", left == 0);
        }
        if (app != null) {
            if (existing) throw Errors.existingCustomer(verificationId);
            Map<String, Object> cols = new HashMap<>();
            cols.put("status", "IDENTITY_CAPTURED");
            cols.put("verification_id", verificationId);
            cols.put("full_name", found.get().fullName());
            cols.put("id_number", found.get().documentNumber());
            cols.put("biometrics_passed", false);
            cols.put("identity_confirmed", false);
            cols.put("biometric_attempts", 0);
            applications.update(app.id(), cols);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verificationId", verificationId);
        out.put("result", result);
        out.put("documentChecks", checks);
        Map<String, Object> rec = new LinkedHashMap<>(registry);
        if (photo != null) rec.put("photo", Base64.getEncoder().encodeToString(photo));
        out.put("registryRecord", rec);
        out.put("existingCustomer", existing);
        return out;
    }

    private Map<String, Object> registryView(RegistryRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", r.source());
        m.put("fullName", r.fullName());
        m.put("dateOfBirth", r.dateOfBirth().toString());
        m.put("gender", r.gender());
        if ("NATIONAL_ID".equals(r.documentType())) m.put("idNumber", r.documentNumber());
        else m.put("passportNumber", r.documentNumber());
        m.put("placeOfBirth", r.placeOfBirth());
        m.put("nationality", r.nationality());
        return m;
    }

    private record Doc(String number, String serial, String surname, LocalDate dob, LocalDate expiry, String country) {}

    private Doc read(DocumentRequest req) {
        if (req.mrz() != null && req.mrz().line1() != null && req.mrz().line2() != null) {
            try {
                Mrz m = Mrz.parse(req.mrz().line1(), req.mrz().line2());
                return new Doc(m.documentNumber(), null, m.surname(), m.dateOfBirth(), m.expiryDate(), m.issuingState());
            } catch (Mrz.MrzException e) {
                throw Errors.validation("mrz", "MRZ could not be read: " + e.getMessage());
            }
        }
        Ocr o = req.ocr();
        if (o == null || o.documentNumber() == null || o.documentNumber().isBlank()) {
            throw Errors.validation("ocr.documentNumber", "ocr.documentNumber or mrz is required");
        }
        return new Doc(o.documentNumber().replace(" ", "").toUpperCase(), o.serialNumber(), o.surname(),
                date("ocr.dateOfBirth", o.dateOfBirth()), date("ocr.expiryDate", o.expiryDate()), null);
    }

    private static LocalDate date(String field, String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw Errors.validation(field, field + " must be YYYY-MM-DD");
        }
    }

    private static String norm(String s) {
        return s.toUpperCase().replaceAll("[^A-Z]", "");
    }

    private static String iso3(String c) {
        return switch (c.toUpperCase()) {
            case "KE" -> "KEN";
            case "GB" -> "GBR";
            case "US" -> "USA";
            default -> c.toUpperCase();
        };
    }

    // ------------------------------------------------------------------ B-5

    public record Face(String image, @DecimalMin("0") @DecimalMax("1") BigDecimal livenessScore,
                       @Size(max = 40) String livenessVendor) {}

    public record Fingerprint(@Pattern(regexp = "RIGHT_THUMB|LEFT_THUMB|RIGHT_INDEX|LEFT_INDEX") String finger,
                              String template, @Min(0) @Max(100) Integer quality) {}

    public record BiometricRequest(@Size(max = 40) String applicationId, @Size(max = 40) String verificationId,
                                   @Size(max = 30) String customerId, @Size(max = 40) String lookupRef,
                                   @Valid Face face, @Valid Fingerprint fingerprint) {}

    @PostMapping("/biometrics/verify")
    @Transactional(noRollbackFor = StmException.class)
    public Map<String, Object> verifyBiometrics(Ctx ctx, @Valid @RequestBody BiometricRequest req) {
        if (req.applicationId() != null) return onboardingBiometrics(ctx, req);
        if (req.customerId() != null) return customerBiometrics(ctx, req);
        throw Errors.validation("applicationId", "applicationId (account opening) or customerId (card services) is required");
    }

    private Map<String, Object> onboardingBiometrics(Ctx ctx, BiometricRequest req) {
        Application app = applications.expect(applications.mine(ctx, req.applicationId()), "IDENTITY_CAPTURED");
        if (req.verificationId() != null && !req.verificationId().equals(app.verificationId())) {
            throw Errors.validation("verificationId", "verificationId does not belong to this application");
        }
        if (req.face() == null || req.fingerprint() == null) {
            throw Errors.validation(req.face() == null ? "face" : "fingerprint", "Face and fingerprint are both required");
        }
        RegistryRecord r = registryOfVerification(app.verificationId());
        IdentityServices.MatchResult face = identity.matchFace(r, Captures.decode("face.image", req.face().image()),
                req.face().livenessScore() == null ? 0 : req.face().livenessScore().doubleValue());
        IdentityServices.MatchResult finger = identity.matchFingerprint(r, finger(req), null, quality(req));
        boolean pass = face.match() && finger.match();
        int attempts = app.biometricAttempts() + (pass ? 0 : 1);
        int left = Math.max(0, MAX_BIOMETRIC_ATTEMPTS - attempts);
        String checkId = record(ctx, req, app.id(), null, app.verificationId(), face, finger, pass, left);
        if (!pass) {
            applications.update(app.id(), Map.of("biometric_attempts", attempts));
            audit.session(ctx, "BIOMETRICS_FAILED", "applicationId", app.id(), "checkId", checkId, "face", face.result(),
                    "fingerprint", finger.result(), "attemptsLeft", left);
            throw Errors.biometricNoMatch(left).detail("face", face.result()).detail("fingerprint", finger.result())
                    .detail("offerTeller", left == 0);
        }
        applications.update(app.id(), Map.of("status", "BIOMETRICS_VERIFIED", "biometrics_passed", true));
        audit.session(ctx, "BIOMETRICS_VERIFIED", "applicationId", app.id(), "checkId", checkId,
                "faceScore", face.score(), "fingerprintScore", finger.score());
        return response(face, finger, left, null);
    }

    private Map<String, Object> customerBiometrics(Ctx ctx, BiometricRequest req) {
        if (req.lookupRef() == null) throw Errors.validation("lookupRef", "lookupRef from C-1 is required");
        record Lookup(String customerId, boolean otp, boolean finger) {}
        Lookup lk = jdbc.sql("SELECT customer_id, otp_verified, finger_verified FROM customer_lookups WHERE ref = :r AND session_id = :s")
                .param("r", req.lookupRef()).param("s", ctx.sessionId())
                .query((rs, n) -> new Lookup(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3))).optional()
                .orElseThrow(() -> Errors.notFound("LOOKUP_NOT_FOUND", "Look up the customer first (C-1)", "Tafuta mteja kwanza"));
        if (!lk.customerId().equals(req.customerId())) throw Errors.validation("customerId", "customerId does not match the lookup");
        if (!lk.otp()) throw Errors.wrongState("Verify the one-time code first (B-10).").detail("nextStep", "OTP");
        if (req.fingerprint() == null) throw Errors.validation("fingerprint", "fingerprint is required");
        CoreBanking.Customer c = core.customer(req.customerId())
                .orElseThrow(() -> Errors.notFound("CUSTOMER_NOT_FOUND", "Customer not found", "Mteja hakupatikana"));
        RegistryRecord r = identity.lookup("NATIONAL_ID", "KEN", c.idNumber())
                .orElseThrow(() -> Errors.identityMismatch("NOT_FOUND", 0));
        long failures = jdbc.sql("SELECT count(*) FROM biometric_checks WHERE customer_id = :c AND session_id = :s AND overall = 'FAIL'")
                .param("c", c.id()).param("s", ctx.sessionId()).query(Long.class).single();
        if (failures >= MAX_BIOMETRIC_ATTEMPTS) throw Errors.biometricNoMatch(0).detail("offerTeller", true);
        IdentityServices.MatchResult face = req.face() == null ? null
                : identity.matchFace(r, Captures.decode("face.image", req.face().image()),
                    req.face().livenessScore() == null ? 0 : req.face().livenessScore().doubleValue());
        IdentityServices.MatchResult finger = identity.matchFingerprint(r, finger(req), null, quality(req));
        boolean pass = finger.match() && (face == null || face.match());
        int left = (int) Math.max(0, MAX_BIOMETRIC_ATTEMPTS - failures - (pass ? 0 : 1));
        String checkId = record(ctx, req, null, c.id(), null, face, finger, pass, left);
        if (!pass) {
            audit.session(ctx, "BIOMETRICS_FAILED", "customerId", c.id(), "checkId", checkId, "attemptsLeft", left);
            throw Errors.biometricNoMatch(left).detail("fingerprint", finger.result()).detail("offerTeller", left == 0);
        }
        jdbc.sql("UPDATE customer_lookups SET finger_verified = true WHERE ref = :r").param("r", req.lookupRef()).update();
        String token = customerTokens.issue(ctx.sessionId(), c.id(), "VERIFIED_IDENTITY");
        sessions.setCustomer(ctx.sessionId(), c.id(), c.fullName());
        audit.session(ctx, "CUSTOMER_VERIFIED", "customerId", c.id(), "method", "OTP_FINGERPRINT", "checkId", checkId);
        return response(face, finger, left, token);
    }

    private static String finger(BiometricRequest req) {
        return req.fingerprint().finger() == null ? "RIGHT_THUMB" : req.fingerprint().finger();
    }

    private static int quality(BiometricRequest req) {
        return req.fingerprint().quality() == null ? 80 : req.fingerprint().quality();
    }

    private String record(Ctx ctx, BiometricRequest req, String applicationId, String customerId, String verificationId,
                          IdentityServices.MatchResult face, IdentityServices.MatchResult finger, boolean pass, int left) {
        String id = Ids.next("bio");
        jdbc.sql("""
                INSERT INTO biometric_checks (id, session_id, application_id, customer_id, verification_id, face_result,
                    face_score, liveness_score, finger, finger_result, finger_score, finger_quality, overall, attempts_left)
                VALUES (:id, :s, :a, :c, :v, :fr, :fs, :ls, :f, :gr, :gs, :q, :o, :l)""")
                .param("id", id).param("s", ctx.sessionId()).param("a", applicationId).param("c", customerId)
                .param("v", verificationId).param("fr", face == null ? null : face.result())
                .param("fs", face == null ? null : BigDecimal.valueOf(face.score()))
                .param("ls", req.face() == null ? null : req.face().livenessScore())
                .param("f", finger(req)).param("gr", finger.result()).param("gs", BigDecimal.valueOf(finger.score()))
                .param("q", quality(req)).param("o", pass ? "PASS" : "FAIL").param("l", left).update();
        if (req.face() != null) captures.store("BIOMETRIC", id, "FACE", Captures.decode("face.image", req.face().image()));
        return id;
    }

    private static Map<String, Object> response(IdentityServices.MatchResult face, IdentityServices.MatchResult finger,
                                                int left, String customerToken) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (face != null) out.put("face", Map.of("result", face.result(), "score", face.score(), "against", "IPRS_PHOTO"));
        out.put("fingerprint", Map.of("result", finger.result(), "score", finger.score(), "against", "IPRS"));
        out.put("overall", "PASS");
        out.put("attemptsLeft", left);
        if (customerToken != null) out.put("customerToken", customerToken);
        return out;
    }

    RegistryRecord registryOfVerification(String verificationId) {
        record V(String type, String country, String number) {}
        V v = jdbc.sql("SELECT document_type, country, document_number FROM identity_verifications WHERE id = :id")
                .param("id", verificationId).query((rs, n) -> new V(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional().orElseThrow(() -> Errors.notFound("VERIFICATION_NOT_FOUND", "Scan the document first",
                        "Changanua kitambulisho kwanza"));
        return identity.lookup(v.type(), v.country(), v.number()).orElseThrow(() -> Errors.identityMismatch("NOT_FOUND", 0));
    }
}
