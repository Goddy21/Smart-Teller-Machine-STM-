package com.stm.middleware.stm.onboarding;

import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.backend.IdentityServices;
import com.stm.middleware.backend.IdentityServices.RegistryRecord;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.device.DeviceHealthService;
import com.stm.middleware.stm.receipts.SmsService;
import com.stm.middleware.stm.support.Captures;
import com.stm.middleware.stm.support.CardNames;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Account opening (B-3, B-6, B-7, B-8, B-11 .. B-14). One application collects consent, identity, KYC and
 * signature, then creates the customer (CIF) and the account in core in a single submit (B-12).
 * Order: B-3 consent -> B-4 ID -> B-5 biometrics -> B-6 confirm -> B-8 KYC -> B-9/B-10 phone -> B-11 signature -> B-12.
 */
@RestController
@RequestMapping("/stm/v1")
public class OnboardingController {
    private final Applications applications;
    private final IdentityController identityController;
    private final IdentityServices identity;
    private final CoreBanking core;
    private final DeviceHealthService health;
    private final SessionStore sessions;
    private final CustomerTokens customerTokens;
    private final Captures captures;
    private final SmsService sms;
    private final Idempotency idempotency;
    private final Refs refs;
    private final JdbcClient jdbc;
    private final Json json;
    private final StmAudit audit;

    public OnboardingController(Applications applications, IdentityController identityController,
                                IdentityServices identity, CoreBanking core, DeviceHealthService health,
                                SessionStore sessions, CustomerTokens customerTokens, Captures captures, SmsService sms,
                                Idempotency idempotency, Refs refs, JdbcClient jdbc, Json json, StmAudit audit) {
        this.applications = applications;
        this.identityController = identityController;
        this.identity = identity;
        this.core = core;
        this.health = health;
        this.sessions = sessions;
        this.customerTokens = customerTokens;
        this.captures = captures;
        this.sms = sms;
        this.idempotency = idempotency;
        this.refs = refs;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ B-3

    public record Consent(@NotBlank String code, @NotNull Boolean accepted) {}

    public record StartRequest(@NotBlank @Size(max = 30) String productCode, @NotBlank @Size(max = 40) String termsId,
                               @NotEmpty List<@Valid Consent> consents, OffsetDateTime acceptedAt) {}

    @PostMapping("/onboarding/applications")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> start(Ctx ctx, @Valid @RequestBody StartRequest req) {
        health.requireService(ctx.terminalId(), "accountOpening");
        long product = jdbc.sql("SELECT count(*) FROM products WHERE code = :c AND active AND channel_stm")
                .param("c", req.productCode()).query(Long.class).single();
        if (product == 0) throw Errors.validation("productCode", "Unknown product " + req.productCode());
        String consentsJson = jdbc.sql("SELECT consents FROM legal_terms WHERE terms_id = :t AND language = 'en' AND active")
                .param("t", req.termsId()).query(String.class).optional()
                .orElseThrow(() -> Errors.of(HttpStatus.CONFLICT, "TERMS_OUTDATED", "These terms are no longer current. Please read them again.",
                        "Masharti haya si ya sasa. Tafadhali yasome tena.", false));
        Map<String, Boolean> given = new HashMap<>();
        req.consents().forEach(c -> given.put(c.code(), c.accepted()));
        for (Map<String, Object> c : json.read(consentsJson, Json.LIST_OF_MAPS)) {
            if (Boolean.TRUE.equals(c.get("required")) && !Boolean.TRUE.equals(given.get(String.valueOf(c.get("code"))))) {
                throw Errors.consentRequired(String.valueOf(c.get("code")));
            }
        }
        String id = Ids.next("app");
        OffsetDateTime acceptedAt = req.acceptedAt() != null ? req.acceptedAt() : OffsetDateTime.now();
        jdbc.sql("""
                INSERT INTO applications (id, session_id, terminal_id, product_code, terms_id, consents, accepted_at, status, expires_at)
                VALUES (:id, :s, :t, :p, :terms, :c, :a, 'CONSENTED', now() + interval '30 minutes')""")
                .param("id", id).param("s", ctx.sessionId()).param("t", ctx.terminalId()).param("p", req.productCode())
                .param("terms", req.termsId()).param("c", json.write(req.consents())).param("a", acceptedAt).update();
        sessions.setFlow(ctx.sessionId(), "ACCOUNT_OPENING");
        audit.session(ctx, "APPLICATION_STARTED", "applicationId", id, "productCode", req.productCode(),
                "termsId", req.termsId(), "consents", req.consents());
        Application a = applications.find(id).orElseThrow();
        return Map.of("applicationId", id, "status", a.status(), "expiresAt", a.expiresAt());
    }

    // ------------------------------------------------------------------ B-6

    public record ConfirmRequest(@NotBlank String verificationId, @NotNull Boolean confirmed) {}

    @PostMapping("/onboarding/applications/{id}/identity-confirm")
    @Transactional
    public Map<String, Object> confirm(Ctx ctx, @PathVariable String id, @Valid @RequestBody ConfirmRequest req) {
        Application a = applications.expect(applications.mine(ctx, id), "BIOMETRICS_VERIFIED");
        if (!req.verificationId().equals(a.verificationId())) {
            throw Errors.validation("verificationId", "verificationId does not belong to this application");
        }
        String status = req.confirmed() ? "IDENTITY_VERIFIED" : "CONSENTED";
        applications.update(id, Map.of("status", status, "identity_confirmed", req.confirmed()));
        audit.session(ctx, req.confirmed() ? "IDENTITY_CONFIRMED" : "IDENTITY_REJECTED_BY_CUSTOMER", "applicationId", id);
        return Map.of("applicationId", id, "status", status);
    }

    // ------------------------------------------------------------------ B-7

    public record KraRequest(@NotBlank @Size(max = 20) String kraPin, @NotBlank @Size(max = 30) String idNumber) {}

    @PostMapping("/kyc/kra-pin/validate")
    public Map<String, Object> kra(Ctx ctx, @Valid @RequestBody KraRequest req) {
        IdentityServices.KraResult r = identity.validateKraPin(req.kraPin(), req.idNumber());
        if (!r.valid() || !r.matchesId()) throw Errors.kraPinInvalid(r.status());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", true);
        out.put("taxpayerName", r.taxpayerName());
        out.put("matchesId", true);
        out.put("status", r.status());
        return out;
    }

    // ------------------------------------------------------------------ B-8

    public record Address(@NotBlank @Size(max = 80) String line1, @Size(max = 60) String town, @Size(max = 40) String county) {}

    public record KycRequest(@NotBlank @Size(max = 20) String kraPin, @NotBlank @Size(max = 20) String mobile,
                             @NotNull @Valid Address address, @Size(max = 80) String occupation,
                             @Size(max = 80) String sourceOfFunds) {}

    @PatchMapping("/onboarding/applications/{id}/kyc")
    @Transactional
    public Map<String, Object> kyc(Ctx ctx, @PathVariable String id, @Valid @RequestBody KycRequest req) {
        Application a = applications.mine(ctx, id);
        if ("REFERRED".equals(a.status())) throw Errors.screeningReferred(id);
        applications.expect(a, "IDENTITY_VERIFIED", "KYC_CAPTURED");
        IdentityServices.KraResult kra = identity.validateKraPin(req.kraPin(), a.idNumber());
        if (!kra.valid() || !kra.matchesId()) throw Errors.kraPinInvalid(kra.status());
        String mobile = SmsService.normalise("mobile", req.mobile());
        RegistryRecord r = identityController.registryOfVerification(a.verificationId());
        IdentityServices.Screening screening = identity.screen(r);
        boolean referred = !screening.clear() && !"CLEARED".equals(a.referralStatus());
        String status = referred ? "REFERRED" : "KYC_CAPTURED";
        Map<String, Object> cols = new HashMap<>();
        cols.put("kra_pin", req.kraPin().trim().toUpperCase());
        cols.put("mobile", mobile);
        cols.put("address", json.write(req.address()));
        cols.put("occupation", req.occupation());
        cols.put("source_of_funds", req.sourceOfFunds());
        cols.put("screening", json.write(screening));
        cols.put("status", status);
        cols.put("otp_verified", false);
        if (referred) cols.put("referral_status", "PENDING");
        applications.update(id, cols);
        audit.session(ctx, referred ? "APPLICATION_REFERRED" : "KYC_CAPTURED", "applicationId", id,
                "sanctions", screening.sanctions(), "pep", screening.pep());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applicationId", id);
        out.put("status", status);
        out.put("screening", Map.of("sanctions", screening.sanctions(), "pep", screening.pep()));
        if (referred) out.put("nextStep", "TELLER");
        return out;
    }

    // ------------------------------------------------------------------ B-11

    public record SignatureRequest(@NotBlank String image, @Min(1) Integer strokes,
                                   @Pattern(regexp = "STYLUS|FINGER") String capturedWith) {}

    @PostMapping("/onboarding/applications/{id}/signature")
    @Transactional
    public Map<String, Object> signature(Ctx ctx, @PathVariable String id, @Valid @RequestBody SignatureRequest req) {
        Application a = applications.expect(applications.mine(ctx, id), "PHONE_VERIFIED", "SIGNATURE_CAPTURED");
        byte[] png = Captures.decode("image", req.image());
        if (png == null || png.length < 32) throw Errors.validation("image", "The signature image is empty");
        String sigId = Ids.next("sig");
        captures.store("APPLICATION", id, "SIGNATURE", png);
        applications.update(a.id(), Map.of("signature_id", sigId, "status", "SIGNATURE_CAPTURED"));
        audit.session(ctx, "SIGNATURE_CAPTURED", "applicationId", id, "signatureId", sigId,
                "strokes", req.strokes(), "capturedWith", req.capturedWith());
        return Map.of("signatureId", sigId, "status", "SIGNATURE_CAPTURED");
    }

    // ------------------------------------------------------------------ B-12

    public record SubmitRequest(@Size(max = 30) String productCode, @Size(max = 20) String branchCode,
                                @Pattern(regexp = "CLASSIC|GOLD") String cardProduct,
                                @Size(max = 26) @Pattern(regexp = "[A-Z .'-]*") String nameOnCard) {}

    @PostMapping("/onboarding/applications/{id}/submit")
    public ResponseEntity<Object> submit(Ctx ctx, @PathVariable String id, @Valid @RequestBody SubmitRequest req) {
        return idempotency.run(ctx, "B-12:" + id, req, HttpStatus.CREATED, () -> doSubmit(ctx, id, req));
    }

    Map<String, Object> doSubmit(Ctx ctx, String id, SubmitRequest req) {
        Application a = applications.expect(applications.mine(ctx, id), "SIGNATURE_CAPTURED");
        if (req.productCode() != null && !req.productCode().equals(a.productCode())) {
            throw Errors.validation("productCode", "productCode does not match the application");
        }
        RegistryRecord r = identityController.registryOfVerification(a.verificationId());
        String accountType = jdbc.sql("SELECT account_type FROM products WHERE code = :c").param("c", a.productCode())
                .query(String.class).single();
        String branch = req.branchCode() != null ? req.branchCode() : ctx.device().branchCode();
        CoreBanking.Opened opened = core.openCustomerAndAccount(new CoreBanking.NewCustomer(r.documentNumber(),
                r.fullName(), r.firstName(), a.mobile(), a.kraPin(), a.address()), a.productCode(), accountType, branch);
        String nameOnCard = req.nameOnCard() != null && !req.nameOnCard().isBlank() ? req.nameOnCard()
                : CardNames.options(r.givenNames(), r.surname()).get(0);
        String cardProduct = req.cardProduct() != null ? req.cardProduct() : "CLASSIC";
        String ref = refs.next("ACC");
        Map<String, Object> cols = new HashMap<>();
        cols.put("status", "ACCOUNT_OPENED");
        cols.put("customer_id", opened.customerId());
        cols.put("account_id", opened.account().id());
        cols.put("account_number", opened.account().number());
        cols.put("card_product", cardProduct);
        cols.put("name_on_card", nameOnCard);
        cols.put("reference", ref);
        applications.update(id, cols);
        String token = customerTokens.issue(ctx.sessionId(), opened.customerId(), "ONBOARDING");
        sessions.setCustomer(ctx.sessionId(), opened.customerId(), r.fullName());
        String branchName = jdbc.sql("SELECT name FROM branches WHERE code = :c").param("c", opened.account().branch())
                .query(String.class).optional().orElse(opened.account().branch());
        String productName = jdbc.sql("SELECT name FROM products WHERE code = :c").param("c", a.productCode())
                .query(String.class).single();
        SmsService.Sent sent = sms.send(ctx.sessionId(), "ACCOUNT_OPENED", a.mobile(), ctx.lang(), Map.of(
                "name", r.firstName(), "product", productName, "account", display(opened.account().number()),
                "branch", branchName, "ref", ref));
        audit.session(ctx, "ACCOUNT_OPENED", "applicationId", id, "customerId", opened.customerId(),
                "accountId", opened.account().id(), "reference", ref);
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("accountId", opened.account().id());
        account.put("accountNumber", opened.account().number());
        account.put("display", display(opened.account().number()));
        account.put("product", a.productCode());
        account.put("branch", branchName + " (" + opened.account().branch() + ")");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applicationId", id);
        out.put("status", "ACCOUNT_OPENED");
        out.put("customerId", opened.customerId());
        out.put("account", account);
        out.put("customerToken", token);
        out.put("cardOrderAllowed", health.services(ctx.terminalId()).get("cardServices"));
        out.put("nameOnCard", nameOnCard);
        out.put("reference", ref);
        out.put("smsSent", "QUEUED".equals(sent.status()));
        return out;
    }

    /** 012345678901 -> 0123 4567 8901 */
    static String display(String number) {
        return number.replaceAll("(.{4})(?!$)", "$1 ");
    }

    // ------------------------------------------------------------------ B-13 / B-14

    @GetMapping("/onboarding/applications/{id}")
    @AllowEndedSession
    public Map<String, Object> get(Ctx ctx, @PathVariable String id) {
        Application a = applications.mine(ctx, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applicationId", a.id());
        out.put("status", a.status());
        out.put("nextStep", a.nextStep());
        out.put("expiresAt", a.expiresAt());
        out.put("productCode", a.productCode());
        if (!"NONE".equals(a.referralStatus())) out.put("referral", a.referralStatus());
        if (a.tellerOverride() != null) out.put("tellerOverride", a.tellerOverride());
        if (a.accountNumber() != null) out.put("accountNumber", a.accountNumber());
        return out;
    }

    public record CancelRequest(@Size(max = 40) String reason, @Size(max = 30) String atStep) {}

    @PostMapping("/onboarding/applications/{id}/cancel")
    @AllowEndedSession
    @Transactional
    public Map<String, Object> cancel(Ctx ctx, @PathVariable String id, @RequestBody(required = false) CancelRequest req) {
        Application a = applications.find(id).filter(x -> x.sessionId().equals(ctx.sessionId()))
                .orElseThrow(() -> Errors.notFound("APPLICATION_NOT_FOUND", "Application not found", "Ombi halikupatikana"));
        if ("ACCOUNT_OPENED".equals(a.status())) throw Errors.wrongState("The account is already open.");
        String reason = req == null || req.reason() == null ? "CUSTOMER_CANCELLED" : req.reason();
        String atStep = req == null ? null : req.atStep();
        Map<String, Object> cols = new HashMap<>();
        cols.put("status", "CANCELLED");
        cols.put("cancel_reason", reason);
        cols.put("cancelled_at_step", atStep);
        applications.update(id, cols);
        // Data minimisation: images and registry details go; the audit journal keeps what happened.
        jdbc.sql("""
                DELETE FROM captures WHERE owner_id = :a
                   OR owner_id IN (SELECT id FROM identity_verifications WHERE application_id = :a)
                   OR owner_id IN (SELECT id FROM biometric_checks WHERE application_id = :a)""")
                .param("a", id).update();
        jdbc.sql("UPDATE identity_verifications SET registry = NULL, ocr = NULL, mrz = NULL WHERE application_id = :a")
                .param("a", id).update();
        jdbc.sql("UPDATE applications SET address = NULL, kra_pin = NULL WHERE id = :a").param("a", id).update();
        audit.session(ctx, "APPLICATION_CANCELLED", "applicationId", id, "reason", reason, "atStep", atStep);
        return Map.of("applicationId", id, "status", "CANCELLED", "dataRetention", "DELETED_EXCEPT_AUDIT");
    }
}
