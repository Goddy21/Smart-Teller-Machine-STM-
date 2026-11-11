package com.stm.middleware.stm.services;

import com.stm.middleware.backend.CardSystem;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.backend.IdentityServices;
import com.stm.middleware.stm.cards.Owned;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.onboarding.KycChecks;
import com.stm.middleware.stm.receipts.SmsService;
import com.stm.middleware.stm.support.Captures;
import com.stm.middleware.stm.support.SignatureCheck;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Services for customers the bank already knows, including those from before the Smart Teller (M-1 .. M-8):
 *   M-1 profile (what is on file and what is missing), M-2 reactivate a dormant account,
 *   M-3 enrol fingerprints with the bank, M-4 / M-5 change the phone number, M-6 / M-7 specimen signature.
 * All of them need the customer verified with a code and thumbprint (C-1, B-9, B-10, B-5), except that a customer
 * whose phone is lost may change the number after face + thumbprint (B-5 with purpose MOBILE_CHANGE).
 */
@RestController
@RequestMapping("/stm/v1")
public class CustomerServicesController {
    private final CoreBanking core;
    private final CardSystem cms;
    private final IdentityServices identity;
    private final KycChecks kyc;
    private final Owned owned;
    private final Safeguards safeguards;
    private final ServiceEvents events;
    private final SmsService sms;
    private final Refs refs;
    private final JdbcClient jdbc;
    private final StmAudit audit;

    private final org.springframework.beans.factory.ObjectProvider<com.stm.middleware.backend.sim.SimControl> sim;

    /** Test mode (Simulator page, "Accept everything"): the kiosk's input is accepted as it is. */
    private boolean testMode() {
        var c = sim.getIfAvailable();
        return c != null && c.acceptAll();
    }

    public CustomerServicesController(CoreBanking core, CardSystem cms, IdentityServices identity, KycChecks kyc, Owned owned,
                                      Safeguards safeguards, ServiceEvents events, SmsService sms, Refs refs,
                                      JdbcClient jdbc, StmAudit audit,
            org.springframework.beans.factory.ObjectProvider<com.stm.middleware.backend.sim.SimControl> sim) {
        this.sim = sim;
        this.core = core;
        this.cms = cms;
        this.identity = identity;
        this.kyc = kyc;
        this.owned = owned;
        this.safeguards = safeguards;
        this.events = events;
        this.sms = sms;
        this.refs = refs;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ M-1

    /** What the bank holds for this customer and what the kiosk should offer to complete. */
    @GetMapping("/customers/{customerId}/profile")
    public Map<String, Object> profile(Ctx ctx, @PathVariable String customerId) {
        ctx.requireCustomer(customerId);
        CoreBanking.Customer c = customer(customerId);
        CoreBanking.Biometrics bio = core.biometrics(customerId);
        Optional<CoreBanking.Signature> sig = core.signature(customerId);
        Map<String, Object> kycStatus = kyc.status(customerId);
        List<CoreBanking.Account> accounts = core.accounts(customerId);
        List<CardSystem.Card> cards = cms.cards(customerId).stream().filter(x -> !"DESTROYED".equals(x.status())).toList();
        long hold = safeguards.holdHoursLeft(customerId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerId", customerId);
        out.put("firstName", c.firstName());
        out.put("maskedMobile", SmsService.mask(c.mobile()));
        out.put("mobileChangedAt", core.mobileChangedAt(customerId).orElse(null));
        out.put("mobileHold", Map.of("active", hold > 0, "hoursLeft", hold));
        out.put("verifiedBy", ctx.customerAuth());

        Map<String, Object> k = new LinkedHashMap<>();
        for (String f : List.of("status", "nextReviewDue", "kraPinOnFile", "selfServiceAllowed", "flags")) k.put(f, kycStatus.get(f));
        out.put("kyc", k);

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("enrolled", bio.enrolled());
        b.put("fingers", bio.fingers());
        b.put("enrolledAt", bio.enrolledAt());
        b.put("source", bio.source());
        out.put("biometrics", b);

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("onFile", sig.isPresent());
        s.put("capturedAt", sig.map(CoreBanking.Signature::capturedAt).orElse(null));
        s.put("source", sig.map(CoreBanking.Signature::source).orElse(null));
        out.put("signature", s);

        long dormant = accounts.stream().filter(CoreBanking.Account::dormant).count();
        out.put("accounts", Map.of("total", accounts.size(), "active", accounts.stream().filter(CoreBanking.Account::active).count(),
                "dormant", dormant, "restricted", accounts.stream().filter(a -> !a.active() && !a.dormant()).count()));
        out.put("cards", Map.of("total", cards.size(), "active", cards.stream().filter(x -> "ACTIVE".equals(x.status())).count(),
                "needAttention", cards.stream().filter(x -> !"ACTIVE".equals(x.status())).count()));

        List<String> todo = new ArrayList<>();
        if (!bio.enrolled()) todo.add("ENROL_BIOMETRICS");
        if (sig.isEmpty()) todo.add("REGISTER_SIGNATURE");
        if (dormant > 0) todo.add("REACTIVATE_ACCOUNT");
        if (!Boolean.TRUE.equals(kycStatus.get("selfServiceAllowed"))) todo.add("KYC_REFRESH_WITH_TELLER");
        out.put("suggestions", todo);
        return out;
    }

    // ------------------------------------------------------------------ M-2

    public record ReactivateRequest(Boolean confirmed) {}

    /** Wakes a dormant account after the customer was re-identified. KYC must be current; frozen accounts go to a teller. */
    @PostMapping("/accounts/{accountId}/reactivate")
    @Transactional
    public Map<String, Object> reactivate(Ctx ctx, @PathVariable String accountId, @RequestBody(required = false) ReactivateRequest req) {
        CoreBanking.Account a = owned.account(ctx, accountId);
        ctx.requireStrongAuth();
        if (a.active()) return accountOut(a, false, null);
        if (!a.dormant()) throw Errors.accountRestricted(a.status());
        if (req == null || !Boolean.TRUE.equals(req.confirmed())) {
            throw Errors.validation("confirmed", "The customer must confirm the reactivation");
        }
        Map<String, Object> k = kyc.status(a.customerId());
        if (!Boolean.TRUE.equals(k.get("selfServiceAllowed"))) {
            audit.session(ctx, "REACTIVATION_REFERRED", "accountId", accountId, "flags", k.get("flags"));
            throw Errors.of(HttpStatus.FORBIDDEN, "KYC_REFRESH_REQUIRED",
                    "We need to update your details before this account can be used again. A teller can help you now.",
                    "Tunahitaji kusasisha maelezo yako kabla akaunti hii kutumika tena. Mhudumu anaweza kukusaidia sasa.", false)
                    .detail("flags", k.get("flags")).detail("offerTeller", true);
        }
        safeguards.requireSettledMobile(a.customerId());
        String ref = refs.next("SVC");
        CoreBanking.Account after = core.reactivate(accountId, ref);
        CoreBanking.Customer c = customer(a.customerId());
        sms.send(ctx.sessionId(), "ACCOUNT_REACTIVATED", c.mobile(), ctx.lang(), Map.of("account", Owned.accountLabel(after), "ref", ref));
        events.record(ctx, a.customerId(), "ACCOUNT_REACTIVATED", Owned.accountLabel(after), ref, "accountId", accountId,
                "dormantSince", a.lastActivityAt() == null ? null : a.lastActivityAt().toLocalDate().toString());
        return accountOut(after, true, ref);
    }

    private static Map<String, Object> accountOut(CoreBanking.Account a, boolean reactivated, String ref) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", a.id());
        out.put("masked", a.masked());
        out.put("type", a.type());
        out.put("status", a.status());
        out.put("reactivated", reactivated);
        if (ref != null) out.put("reference", ref);
        return out;
    }

    // ------------------------------------------------------------------ M-3

    public record Finger(@NotBlank @Pattern(regexp = "RIGHT_THUMB|LEFT_THUMB|RIGHT_INDEX|LEFT_INDEX") String finger,
                         @NotBlank String template, @NotNull @Min(0) @Max(100) Integer quality) {}

    public record EnrolRequest(@NotEmpty @Size(max = 4) List<@Valid Finger> fingerprints, Boolean consent) {}

    /**
     * Registers the customer's fingerprints with the bank (customers from before the STM have none). Each print must be
     * clear (quality 50+) and match the national registry, so only the customer's own fingers can be enrolled.
     */
    @PostMapping("/customers/{customerId}/biometrics/enrol")
    @Transactional
    public Map<String, Object> enrol(Ctx ctx, @PathVariable String customerId, @Valid @RequestBody EnrolRequest req) {
        ctx.requireCustomer(customerId);
        ctx.requireStrongAuth();
        if (!Boolean.TRUE.equals(req.consent())) {
            throw Errors.of(HttpStatus.UNPROCESSABLE_ENTITY, "CONSENT_REQUIRED",
                    "We need your consent to keep your fingerprints.", "Tunahitaji idhini yako kuhifadhi alama zako za vidole.", false);
        }
        CoreBanking.Customer c = customer(customerId);
        IdentityServices.RegistryRecord r = identity.lookup("NATIONAL_ID", "KEN", c.idNumber())
                .orElseThrow(() -> Errors.identityMismatch("NOT_FOUND", 0));
        Set<String> seen = new HashSet<>();
        List<CoreBanking.FingerTemplate> list = new ArrayList<>();
        for (Finger f : req.fingerprints()) {
            if (!seen.add(f.finger())) throw Errors.validation("fingerprints", "Each finger may appear once");
            byte[] tpl = Captures.decode("fingerprints.template", f.template());
            if ((tpl == null || tpl.length == 0) && testMode()) tpl = new byte[]{0};
            if (tpl == null || tpl.length == 0) throw Errors.validation("fingerprints.template", "template is required");
            if (f.quality() < 50 && !testMode()) {
                throw Errors.of(HttpStatus.UNPROCESSABLE_ENTITY, "BIOMETRIC_QUALITY_LOW",
                        "That print was not clear enough. Clean your finger and try again.",
                        "Alama hiyo haikuwa wazi vya kutosha. Safisha kidole chako na ujaribu tena.", true).detail("finger", f.finger());
            }
            if (!identity.matchFingerprint(r, f.finger(), tpl, f.quality()).match()) {
                audit.session(ctx, "BIOMETRIC_ENROLMENT_REJECTED", "customerId", customerId, "finger", f.finger());
                throw Errors.biometricNoMatch(0).detail("finger", f.finger()).detail("offerTeller", true);
            }
            list.add(new CoreBanking.FingerTemplate(f.finger(), tpl, f.quality()));
        }
        boolean first = !core.biometrics(customerId).enrolled();
        CoreBanking.Biometrics b = core.enrolBiometrics(customerId, list, "STM_ENROLMENT");
        String ref = refs.next("SVC");
        sms.send(ctx.sessionId(), "BIOMETRICS_ENROLLED", c.mobile(), ctx.lang(), Map.of());
        events.record(ctx, customerId, "BIOMETRICS_ENROLLED", String.join(", ", b.fingers()), ref, "fingers", b.fingers(), "firstEnrolment", first);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enrolled", true);
        out.put("fingers", b.fingers());
        out.put("enrolledAt", b.enrolledAt());
        out.put("reference", ref);
        return out;
    }

    // ------------------------------------------------------------------ M-4, M-5

    public record MobileChangeRequest(@NotBlank @Size(max = 20) String newMobile) {}

    /**
     * Step 1 of a change of phone number: checks the new number and opens the change. The kiosk then sends a code to the
     * NEW number (B-9 with purpose MOBILE_CHANGE and this changeRef), verifies it (B-10) and confirms (M-5).
     */
    @PostMapping("/customers/{customerId}/mobile-change")
    @Transactional(noRollbackFor = StmException.class)
    public Map<String, Object> startMobileChange(Ctx ctx, @PathVariable String customerId, @Valid @RequestBody MobileChangeRequest req) {
        requireForMobileChange(ctx, customerId);
        CoreBanking.Customer c = customer(customerId);
        String next = SmsService.normalise("newMobile", req.newMobile());
        if (next.equals(c.mobile())) {
            throw Errors.of(HttpStatus.UNPROCESSABLE_ENTITY, "MOBILE_UNCHANGED", "This is already your registered number.",
                    "Hii tayari ndiyo nambari yako iliyosajiliwa.", false);
        }
        if (core.mobileInUse(next, customerId)) {
            audit.session(ctx, "MOBILE_CHANGE_REFUSED", "customerId", customerId, "reason", "IN_USE", "newMobile", SmsService.mask(next));
            throw Errors.of(HttpStatus.CONFLICT, "MOBILE_IN_USE",
                    "This number is registered to another customer. Please talk to a teller.",
                    "Nambari hii imesajiliwa kwa mteja mwingine. Tafadhali zungumza na mhudumu.", false).detail("offerTeller", true);
        }
        safeguards.requireSettledMobile(customerId);
        try {
            kyc.requireNoRecentSwap(next);          // a freshly swapped SIM must not become the number on the account
        } catch (StmException e) {
            audit.session(ctx, "MOBILE_CHANGE_REFUSED", "customerId", customerId, "reason", "SIM_SWAP", "newMobile", SmsService.mask(next));
            throw e;
        }
        jdbc.sql("UPDATE mobile_changes SET status = 'CANCELLED' WHERE session_id = :s AND customer_id = :c AND status = 'PENDING'")
                .param("s", ctx.sessionId()).param("c", customerId).update();
        String ref = Ids.next("mch");
        jdbc.sql("""
                INSERT INTO mobile_changes (ref, session_id, customer_id, old_mobile, new_mobile)
                VALUES (:r, :s, :c, :o, :n)""")
                .param("r", ref).param("s", ctx.sessionId()).param("c", customerId).param("o", c.mobile()).param("n", next).update();
        audit.session(ctx, "MOBILE_CHANGE_STARTED", "changeRef", ref, "customerId", customerId,
                "from", SmsService.mask(c.mobile()), "to", SmsService.mask(next), "verifiedBy", ctx.customerAuth());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changeRef", ref);
        out.put("maskedCurrentMobile", SmsService.mask(c.mobile()));
        out.put("maskedNewMobile", SmsService.mask(next));
        out.put("nextStep", "OTP");
        out.put("otpPurpose", "MOBILE_CHANGE");
        return out;
    }

    /** Step 2: after the code sent to the new number was verified, the number is changed in core banking. */
    @PostMapping("/customers/{customerId}/mobile-change/{changeRef}/confirm")
    @Transactional
    public Map<String, Object> confirmMobileChange(Ctx ctx, @PathVariable String customerId, @PathVariable String changeRef) {
        requireForMobileChange(ctx, customerId);
        record Change(String oldMobile, String newMobile, String status) {}
        Change ch = jdbc.sql("""
                SELECT old_mobile, new_mobile, status FROM mobile_changes
                WHERE ref = :r AND session_id = :s AND customer_id = :c FOR UPDATE""")
                .param("r", changeRef).param("s", ctx.sessionId()).param("c", customerId)
                .query((rs, n) -> new Change(rs.getString(1), rs.getString(2), rs.getString(3))).optional()
                .orElseThrow(() -> Errors.notFound("MOBILE_CHANGE_NOT_FOUND", "Start the phone-number change first",
                        "Anza kubadilisha nambari ya simu kwanza"));
        if ("CONFIRMED".equals(ch.status())) return changed(ch.newMobile(), null);
        if (!"PENDING".equals(ch.status())) throw Errors.wrongState("This change was cancelled. Start again.");
        boolean verified = jdbc.sql("""
                SELECT count(*) FROM otps WHERE subject_id = :r AND purpose = 'MOBILE_CHANGE' AND verified_at IS NOT NULL""")
                .param("r", changeRef).query(Long.class).single() > 0;
        if (!verified) throw Errors.wrongState("Verify the code sent to the new number first (B-10).").detail("nextStep", "OTP");
        String ref = refs.next("SVC");
        core.changeMobile(customerId, ch.newMobile(), "STM", ref);
        jdbc.sql("UPDATE mobile_changes SET status = 'CONFIRMED', confirmed_at = now() WHERE ref = :r").param("r", changeRef).update();
        // Tell both numbers: the old one is how a customer finds out about a change they did not make.
        sms.send(ctx.sessionId(), "MOBILE_CHANGED_OLD", ch.oldMobile(), ctx.lang(), Map.of("mobile", SmsService.mask(ch.newMobile())));
        sms.send(ctx.sessionId(), "MOBILE_CHANGED_NEW", ch.newMobile(), ctx.lang(), Map.of("ref", ref));
        events.record(ctx, customerId, "MOBILE_CHANGED", SmsService.mask(ch.newMobile()), ref,
                "from", SmsService.mask(ch.oldMobile()), "to", SmsService.mask(ch.newMobile()), "verifiedBy", ctx.customerAuth(),
                "holdHours", safeguards.holdHours());
        return changed(ch.newMobile(), ref);
    }

    private Map<String, Object> changed(String mobile, String ref) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changed", true);
        out.put("maskedMobile", SmsService.mask(mobile));
        out.put("holdHours", safeguards.holdHours());
        if (ref != null) out.put("reference", ref);
        return out;
    }

    /** A change of phone number accepts the code + thumbprint token and the face + thumbprint one (phone lost). */
    private static void requireForMobileChange(Ctx ctx, String customerId) {
        if (!ctx.customerIdForMobileChange().equals(customerId)) {
            throw Errors.forbidden("This customer token does not cover that customer.");
        }
        String src = ctx.customerAuth();
        if (!"VERIFIED_IDENTITY".equals(src) && !"BIOMETRIC_ONLY".equals(src)) throw Errors.stepUpRequired();
    }

    // ------------------------------------------------------------------ M-6, M-7

    @GetMapping("/customers/{customerId}/signature")
    public Map<String, Object> signature(Ctx ctx, @PathVariable String customerId) {
        ctx.requireCustomer(customerId);
        Optional<CoreBanking.Signature> sig = core.signature(customerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("onFile", sig.isPresent());
        out.put("signatureId", sig.map(CoreBanking.Signature::id).orElse(null));
        out.put("capturedAt", sig.map(CoreBanking.Signature::capturedAt).orElse(null));
        out.put("source", sig.map(CoreBanking.Signature::source).orElse(null));
        return out;
    }

    public record SignatureRequest(@NotBlank String image, @Min(1) Integer strokes,
                                   @Pattern(regexp = "STYLUS|FINGER") String capturedWith) {}

    /** Registers the specimen signature of a customer who has none, or replaces it. The old one is kept as superseded. */
    @PostMapping("/customers/{customerId}/signature")
    @Transactional
    public Map<String, Object> registerSignature(Ctx ctx, @PathVariable String customerId, @Valid @RequestBody SignatureRequest req) {
        ctx.requireCustomer(customerId);
        ctx.requireStrongAuth();
        byte[] image = Captures.decode("image", req.image());
        if ((image == null || image.length == 0) && testMode()) image = "test-mode signature placeholder".getBytes();
        SignatureCheck.Quality q = SignatureCheck.validate("image", image, testMode());
        safeguards.requireSettledMobile(customerId);
        boolean replaced = core.signature(customerId).isPresent();
        CoreBanking.Signature sig = core.registerSignature(customerId, image, Captures.mimeOf(image), "STM_UPDATE");
        String ref = refs.next("SVC");
        CoreBanking.Customer c = customer(customerId);
        sms.send(ctx.sessionId(), "SIGNATURE_UPDATED", c.mobile(), ctx.lang(), Map.of());
        events.record(ctx, customerId, replaced ? "SIGNATURE_UPDATED" : "SIGNATURE_REGISTERED", null, ref,
                "signatureId", sig.id(), "strokes", req.strokes(), "capturedWith", req.capturedWith(), "inkRatio", q.inkRatio());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signatureId", sig.id());
        out.put("capturedAt", sig.capturedAt());
        out.put("replacedPrevious", replaced);
        out.put("reference", ref);
        return out;
    }

    private CoreBanking.Customer customer(String customerId) {
        return core.customer(customerId)
                .orElseThrow(() -> Errors.notFound("CUSTOMER_NOT_FOUND", "Customer not found", "Mteja hakupatikana"));
    }
}
