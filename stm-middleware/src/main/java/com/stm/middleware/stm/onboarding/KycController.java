package com.stm.middleware.stm.onboarding;

import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.core.Ctx;
import com.stm.middleware.stm.core.Errors;
import com.stm.middleware.stm.core.StmAudit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Checks the kiosk asks for before trusting a phone or offering self-service to an existing customer:
 *   POST /kyc/sim-swap-check            SIM-swap risk of the number a code would go to (B-9 enforces the same rule)
 *   GET  /customers/{id}/kyc-status     KYC current or due, KRA PIN on file, PEP / high-risk flags
 */
@RestController
@RequestMapping("/stm/v1")
public class KycController {
    private final KycChecks checks;
    private final Applications applications;
    private final CoreBanking core;
    private final JdbcClient jdbc;
    private final StmAudit audit;

    public KycController(KycChecks checks, Applications applications, CoreBanking core, JdbcClient jdbc, StmAudit audit) {
        this.checks = checks;
        this.applications = applications;
        this.core = core;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record SimSwapRequest(@Size(max = 40) String lookupRef, @Size(max = 40) String applicationId) {}

    /** Uses the number on record (core banking for a lookup, B-8 for an application), never one typed at the kiosk. */
    @PostMapping("/kyc/sim-swap-check")
    public Map<String, Object> simSwap(Ctx ctx, @Valid @RequestBody SimSwapRequest req) {
        String mobile;
        String subject;
        if (req.lookupRef() != null) {
            String customerId = lookupCustomer(ctx, req.lookupRef());
            mobile = core.customer(customerId).map(CoreBanking.Customer::mobile)
                    .orElseThrow(() -> Errors.notFound("CUSTOMER_NOT_FOUND", "Customer not found", "Mteja hakupatikana"));
            subject = req.lookupRef();
        } else if (req.applicationId() != null) {
            Application a = applications.mine(ctx, req.applicationId());
            if (a.mobile() == null) throw Errors.wrongState("Capture the mobile number first (B-8).").detail("nextStep", "KYC");
            mobile = a.mobile();
            subject = a.id();
        } else {
            throw Errors.validation("lookupRef", "lookupRef (C-1) or applicationId (B-3) is required");
        }
        KycChecks.SimSwap s = checks.simSwap(mobile);
        audit.session(ctx, "SIM_SWAP_CHECKED", "subject", subject, "risk", s.risk(), "hoursSinceSwap", s.hoursSinceSwap());
        Map<String, Object> out = s.view();
        out.put("checkedAt", java.time.OffsetDateTime.now());
        return out;
    }

    /** Needs the customer's token (S-2, B-5, B-12), or the lookupRef from C-1 in this session (before the OTP). */
    @GetMapping("/customers/{customerId}/kyc-status")
    public Map<String, Object> kycStatus(Ctx ctx, @PathVariable String customerId,
                                         @RequestParam(required = false) String lookupRef) {
        if (lookupRef != null && !lookupRef.isBlank()) {
            if (!customerId.equals(lookupCustomer(ctx, lookupRef))) throw Errors.forbidden("This lookup is for another customer.");
        } else {
            ctx.requireCustomer(customerId);
        }
        Map<String, Object> out = checks.status(customerId);
        audit.session(ctx, "KYC_STATUS_CHECKED", "customerId", customerId, "status", out.get("status"),
                "riskRating", out.get("riskRating"));
        return out;
    }

    private String lookupCustomer(Ctx ctx, String lookupRef) {
        return jdbc.sql("SELECT customer_id FROM customer_lookups WHERE ref = :r AND session_id = :s")
                .param("r", lookupRef).param("s", ctx.sessionId()).query(String.class).optional()
                .orElseThrow(() -> Errors.notFound("LOOKUP_NOT_FOUND", "Look up the customer first (C-1)", "Tafuta mteja kwanza"));
    }
}
