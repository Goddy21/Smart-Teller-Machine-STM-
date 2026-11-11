package com.stm.middleware.stm.onboarding;

import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.backend.sim.SimControl;
import com.stm.middleware.common.Hashing;
import com.stm.middleware.stm.core.*;
import com.stm.middleware.stm.receipts.SmsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One-time codes (B-9, B-10): the new customer's phone during account opening (ONBOARDING_PHONE), and the
 * registered phone of an existing customer during card services (CARD_SERVICES). The code is never stored in clear.
 */
@RestController
@RequestMapping("/stm/v1/otp")
public class OtpController {
    static final int LENGTH = 6, TTL_SEC = 300, RESEND_SEC = 60, ATTEMPTS = 3, MAX_SENDS = 4;

    private final JdbcClient jdbc;
    private final Applications applications;
    private final CoreBanking core;
    private final SmsService sms;
    private final StmAudit audit;
    private final ObjectProvider<SimControl> sim;
    private final KycChecks kyc;

    public OtpController(JdbcClient jdbc, Applications applications, CoreBanking core, SmsService sms, StmAudit audit,
                         ObjectProvider<SimControl> sim, KycChecks kyc) {
        this.kyc = kyc;
        this.jdbc = jdbc;
        this.applications = applications;
        this.core = core;
        this.sms = sms;
        this.audit = audit;
        this.sim = sim;
    }

    /** MOBILE_CHANGE sends the code to the NEW number of a pending change (changeRef from M-1). */
    public record SendRequest(@NotBlank @Pattern(regexp = "ONBOARDING_PHONE|CARD_SERVICES|MOBILE_CHANGE") String purpose,
                              @Size(max = 40) String applicationId, @Size(max = 40) String lookupRef,
                              @Size(max = 40) String changeRef,
                              @Size(max = 20) String mobile, @Pattern(regexp = "SMS") String channel) {}

    @PostMapping("/send")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional(noRollbackFor = StmException.class)   // keeps the audit of a blocked send
    public Map<String, Object> send(Ctx ctx, @Valid @RequestBody SendRequest req) {
        String subject;
        String mobile;
        if ("ONBOARDING_PHONE".equals(req.purpose())) {
            if (req.applicationId() == null) throw Errors.validation("applicationId", "applicationId is required");
            Application a = applications.mine(ctx, req.applicationId());
            if ("REFERRED".equals(a.status())) throw Errors.screeningReferred(a.id());
            applications.expect(a, "KYC_CAPTURED");
            if (req.mobile() != null && !SmsService.normalise("mobile", req.mobile()).equals(a.mobile())) {
                throw Errors.validation("mobile", "This is not the mobile number saved on the application (B-8)");
            }
            subject = a.id();
            mobile = a.mobile();
        } else if ("MOBILE_CHANGE".equals(req.purpose())) {
            if (req.changeRef() == null) throw Errors.validation("changeRef", "changeRef from the phone-number change is required");
            mobile = jdbc.sql("""
                    SELECT new_mobile FROM mobile_changes WHERE ref = :r AND session_id = :s AND status = 'PENDING'""")
                    .param("r", req.changeRef()).param("s", ctx.sessionId()).query(String.class).optional()
                    .orElseThrow(() -> Errors.notFound("MOBILE_CHANGE_NOT_FOUND", "Start the phone-number change first",
                            "Anza kubadilisha nambari ya simu kwanza"));
            subject = req.changeRef();
        } else {
            if (req.lookupRef() == null) throw Errors.validation("lookupRef", "lookupRef from C-1 is required");
            String customerId = jdbc.sql("SELECT customer_id FROM customer_lookups WHERE ref = :r AND session_id = :s")
                    .param("r", req.lookupRef()).param("s", ctx.sessionId()).query(String.class).optional()
                    .orElseThrow(() -> Errors.notFound("LOOKUP_NOT_FOUND", "Look up the customer first (C-1)", "Tafuta mteja kwanza"));
            subject = req.lookupRef();
            // Always the phone registered in core, never one typed at the kiosk.
            mobile = core.customer(customerId).map(CoreBanking.Customer::mobile)
                    .orElseThrow(() -> Errors.notFound("CUSTOMER_NOT_FOUND", "Customer not found", "Mteja hakupatikana"));
        }
        // Never text a code to a SIM that was just swapped (403 SIM_SWAP_RISK): the kiosk offers a teller instead.
        try {
            kyc.requireNoRecentSwap(mobile);
        } catch (StmException e) {
            audit.session(ctx, "OTP_BLOCKED_SIM_SWAP", "purpose", req.purpose(), "subject", subject, "mobile", SmsService.mask(mobile));
            throw e;
        }
        record Last(OffsetDateTime sentAt, int sends) {}
        Optional<Last> last = jdbc.sql("""
                SELECT last_sent_at, sends FROM otps WHERE subject_id = :s AND verified_at IS NULL
                ORDER BY created_at DESC LIMIT 1""")
                .param("s", subject).query((rs, n) -> new Last(rs.getObject(1, OffsetDateTime.class), rs.getInt(2))).optional();
        if (last.isPresent()) {
            long since = Duration.between(last.get().sentAt(), OffsetDateTime.now()).toSeconds();
            if (since < RESEND_SEC) throw Errors.resendTooSoon(RESEND_SEC - since);
        }
        long sends = jdbc.sql("SELECT count(*) FROM otps WHERE subject_id = :s").param("s", subject).query(Long.class).single();
        if (sends >= MAX_SENDS) {
            throw Errors.of(HttpStatus.FORBIDDEN, "OTP_TRIES_EXCEEDED", "Too many codes requested. Please ask a teller for help.",
                    "Umeomba nambari nyingi mno. Tafadhali omba msaada wa karani.", false);
        }
        String fixed = sim.getIfAvailable() == null ? null : sim.getIfAvailable().fixedOtp();
        String code = fixed != null ? fixed : Ids.digits(LENGTH);
        String ref = Ids.next("otp");
        jdbc.sql("""
                INSERT INTO otps (ref, session_id, purpose, subject_id, mobile, code_hash, attempts_left, expires_at)
                VALUES (:r, :s, :p, :sub, :m, :h, :a, now() + make_interval(secs => :ttl))""")
                .param("r", ref).param("s", ctx.sessionId()).param("p", req.purpose()).param("sub", subject)
                .param("m", mobile).param("h", Hashing.sha256Hex(ref + ":" + code)).param("a", ATTEMPTS)
                .param("ttl", TTL_SEC).update();
        sms.send(ctx.sessionId(), "OTP", mobile, ctx.lang(), Map.of("code", code));
        audit.session(ctx, "OTP_SENT", "otpRef", ref, "purpose", req.purpose(), "subject", subject,
                "mobile", SmsService.mask(mobile));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("otpRef", ref);
        out.put("maskedMobile", SmsService.mask(mobile));
        out.put("length", LENGTH);
        out.put("expiresInSec", TTL_SEC);
        out.put("resendAfterSec", RESEND_SEC);
        return out;
    }

    public record VerifyRequest(@NotBlank @Size(max = 40) String otpRef, @NotBlank @Pattern(regexp = "\\d{4,8}") String code) {}

    @PostMapping("/verify")
    @Transactional(noRollbackFor = StmException.class)
    public Map<String, Object> verify(Ctx ctx, @Valid @RequestBody VerifyRequest req) {
        record Otp(String purpose, String subject, String hash, int left, OffsetDateTime expires, OffsetDateTime verified) {}
        Otp o = jdbc.sql("""
                SELECT purpose, subject_id, code_hash, attempts_left, expires_at, verified_at FROM otps
                WHERE ref = :r AND session_id = :s FOR UPDATE""")
                .param("r", req.otpRef()).param("s", ctx.sessionId())
                .query((rs, n) -> new Otp(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class)))
                .optional().orElseThrow(() -> Errors.notFound("OTP_NOT_FOUND", "Request a code first", "Omba nambari kwanza"));
        if (o.verified() != null) return Map.of("verified", true, "attemptsLeft", o.left());
        if (o.left() <= 0) {
            throw Errors.of(HttpStatus.FORBIDDEN, "OTP_TRIES_EXCEEDED", "Too many wrong codes. Request a new code.",
                    "Umekosea mara nyingi. Omba nambari mpya.", false);
        }
        if (o.expires().isBefore(OffsetDateTime.now())) throw Errors.otpExpired();
        boolean anyCode = sim.getIfAvailable() != null && sim.getIfAvailable().acceptAll();   // test mode: any code
        if (!anyCode && !Hashing.constantTimeEquals(o.hash(), Hashing.sha256Hex(req.otpRef() + ":" + req.code()))) {
            int left = o.left() - 1;
            jdbc.sql("UPDATE otps SET attempts_left = :l WHERE ref = :r").param("l", left).param("r", req.otpRef()).update();
            audit.session(ctx, "OTP_FAILED", "otpRef", req.otpRef(), "attemptsLeft", left);
            throw Errors.otpIncorrect(left);
        }
        jdbc.sql("UPDATE otps SET verified_at = now() WHERE ref = :r").param("r", req.otpRef()).update();
        if ("ONBOARDING_PHONE".equals(o.purpose())) {
            Application a = applications.expect(applications.mine(ctx, o.subject()), "KYC_CAPTURED");
            applications.update(a.id(), Map.of("otp_verified", true, "status", "PHONE_VERIFIED"));
        } else if ("CARD_SERVICES".equals(o.purpose())) {
            jdbc.sql("UPDATE customer_lookups SET otp_verified = true WHERE ref = :r").param("r", o.subject()).update();
        }   // MOBILE_CHANGE: the confirm call (M-2) checks that this code was verified
        audit.session(ctx, "OTP_VERIFIED", "otpRef", req.otpRef(), "purpose", o.purpose(), "subject", o.subject());
        return Map.of("verified", true, "attemptsLeft", o.left());
    }
}
