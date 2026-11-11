package com.stm.middleware.stm.onboarding;

import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.backend.Telco;
import com.stm.middleware.stm.core.Errors;
import com.stm.middleware.stm.core.StmException;
import com.stm.middleware.stm.receipts.SmsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SIM-swap risk for a phone number, and the KYC status of an existing customer.
 * The OTP service (B-9) refuses to text a code to a number whose SIM was swapped recently: that is how account
 * takeovers usually start.
 */
@Service
public class KycChecks {
    private final Telco telco;
    private final CoreBanking core;
    private final long blockHours;
    private final int lowYears, mediumYears, highYears, dueSoonDays;

    public KycChecks(Telco telco, CoreBanking core,
                     @Value("${stm.kyc.sim-swap-block-hours:72}") long blockHours,
                     @Value("${stm.kyc.review-years.low:3}") int lowYears,
                     @Value("${stm.kyc.review-years.medium:2}") int mediumYears,
                     @Value("${stm.kyc.review-years.high:1}") int highYears,
                     @Value("${stm.kyc.due-soon-days:30}") int dueSoonDays) {
        this.telco = telco;
        this.core = core;
        this.blockHours = blockHours;
        this.lowYears = lowYears;
        this.mediumYears = mediumYears;
        this.highYears = highYears;
        this.dueSoonDays = dueSoonDays;
    }

    public record SimSwap(String maskedMobile, OffsetDateTime lastSwapAt, Long hoursSinceSwap, String risk, boolean otpAllowed) {
        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("maskedMobile", maskedMobile);
            m.put("lastSwapAt", lastSwapAt);
            m.put("hoursSinceSwap", hoursSinceSwap);
            m.put("risk", risk);
            m.put("otpAllowed", otpAllowed);
            return m;
        }
    }

    /** HIGH when the SIM was swapped within stm.kyc.sim-swap-block-hours (72 h); LOW otherwise or when none is on record. */
    public SimSwap simSwap(String mobile) {
        Optional<OffsetDateTime> swapped = telco.lastSimSwap(mobile);
        Long hours = swapped.map(t -> Duration.between(t, OffsetDateTime.now()).toHours()).orElse(null);
        boolean recent = hours != null && hours < blockHours;
        return new SimSwap(SmsService.mask(mobile), swapped.map(t -> t.truncatedTo(ChronoUnit.MINUTES)).orElse(null), hours,
                recent ? "HIGH" : "LOW", !recent);
    }

    /** Throws 403 SIM_SWAP_RISK when a code must not be sent to this number. */
    public void requireNoRecentSwap(String mobile) {
        SimSwap s = simSwap(mobile);
        if (!s.otpAllowed()) {
            StmException e = Errors.of(org.springframework.http.HttpStatus.FORBIDDEN, "SIM_SWAP_RISK",
                    "For your security we can't send a code to this phone right now. Please ask a teller for help.",
                    "Kwa usalama wako hatuwezi kutuma nambari kwa simu hii sasa. Tafadhali omba msaada wa karani.", false);
            throw e.detail("hoursSinceSwap", s.hoursSinceSwap()).detail("blockHours", blockHours).detail("offerTeller", true);
        }
    }

    /** KYC status of an existing customer: review cycle by risk rating, KRA PIN on file, PEP and other flags. */
    public Map<String, Object> status(String customerId) {
        CoreBanking.Customer c = core.customer(customerId)
                .orElseThrow(() -> Errors.notFound("CUSTOMER_NOT_FOUND", "Customer not found", "Mteja hakupatikana"));
        CoreBanking.KycProfile k = core.kycProfile(customerId);
        String rating = k.riskRating() == null ? "LOW" : k.riskRating();
        int years = switch (rating) { case "HIGH" -> highYears; case "MEDIUM" -> mediumYears; default -> lowYears; };
        OffsetDateTime due = k.lastReviewedAt().plusYears(years);
        OffsetDateTime now = OffsetDateTime.now();
        String status = now.isAfter(due) ? "REFRESH_DUE" : now.plusDays(dueSoonDays).isAfter(due) ? "DUE_SOON" : "CURRENT";
        boolean kraOnFile = c.kraPin() != null && !c.kraPin().isBlank();
        boolean highRisk = "HIGH".equals(rating) || k.pep() || !"CLEAR".equals(k.sanctions());

        List<String> reasons = new ArrayList<>();
        if ("REFRESH_DUE".equals(status)) reasons.add("KYC_REFRESH_DUE");
        if (!kraOnFile) reasons.add("KRA_PIN_MISSING");
        if (k.pep()) reasons.add("PEP");
        if ("HIGH".equals(rating)) reasons.add("HIGH_RISK");
        if (!"CLEAR".equals(k.sanctions())) reasons.add("SANCTIONS_REVIEW");
        reasons.addAll(k.flags());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerId", customerId);
        out.put("status", status);
        out.put("lastReviewedAt", k.lastReviewedAt().toLocalDate().toString());
        out.put("nextReviewDue", due.toLocalDate().toString());
        out.put("riskRating", rating);
        out.put("kraPinOnFile", kraOnFile);
        out.put("kraPinMasked", kraOnFile ? c.kraPin().substring(0, 1) + "•••••••" + c.kraPin().substring(c.kraPin().length() - 3) : null);
        out.put("pep", k.pep());
        out.put("sanctions", k.sanctions());
        out.put("highRisk", highRisk);
        out.put("flags", reasons);
        // What the bank holds to recognise this customer: customers from before the STM may have neither.
        out.put("biometricsEnrolled", core.biometrics(customerId).enrolled());
        out.put("signatureOnFile", core.signature(customerId).isPresent());
        // Self-service is allowed while KYC is current and nothing needs a person to look at it.
        out.put("selfServiceAllowed", !"REFRESH_DUE".equals(status) && kraOnFile && !highRisk);
        return out;
    }
}
