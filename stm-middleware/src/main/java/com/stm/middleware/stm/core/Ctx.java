package com.stm.middleware.stm.core;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Everything a Gateway call knows about its caller: the kiosk (device token), the customer session (X-Session-Id),
 * the language and, on demand, the verified customer (X-Customer-Token). Resolved by {@link CtxResolver}.
 */
public final class Ctx {
    private final DevicePrincipal device;
    private final StmSession session;
    private final HttpServletRequest request;
    private final CustomerTokens tokens;
    private String customerId;
    private boolean limitedToken;      // BIOMETRIC_ONLY: face + thumbprint without the phone code

    Ctx(DevicePrincipal device, StmSession session, HttpServletRequest request, CustomerTokens tokens) {
        this.device = device;
        this.session = session;
        this.request = request;
        this.tokens = tokens;
    }

    public DevicePrincipal device() { return device; }
    public long terminalId() { return device.terminalId(); }
    public String deviceId() { return device.deviceId(); }
    public boolean hasSession() { return session != null; }

    public StmSession session() {
        if (session == null) throw Errors.missingHeader("X-Session-Id");
        return session;
    }

    public String sessionId() { return session().id(); }

    /** Accept-Language (en / sw), else the session's language, else English. */
    public String lang() {
        String h = request.getHeader("Accept-Language");
        if (h != null) {
            String l = h.trim().toLowerCase();
            if (l.startsWith("sw")) return "sw";
            if (l.startsWith("en")) return "en";
        }
        return session != null && session.language() != null ? session.language() : "en";
    }

    public boolean sw() { return "sw".equals(lang()); }

    /**
     * The verified customer (X-Customer-Token), or CUSTOMER_TOKEN_REQUIRED / CUSTOMER_TOKEN_INVALID.
     * A BIOMETRIC_ONLY token (phone lost) is refused here with STEP_UP_REQUIRED: it only works for
     * {@link #customerIdForMobileChange()}.
     */
    public String customerId() {
        resolveCustomer();
        if (limitedToken) throw Errors.stepUpRequired();
        return customerId;
    }

    /** As {@link #customerId()}, and also accepts the face + thumbprint token issued when the phone is lost. */
    public String customerIdForMobileChange() {
        resolveCustomer();
        return customerId;
    }

    private void resolveCustomer() {
        if (customerId != null) return;
        String token = request.getHeader("X-Customer-Token");
        customerId = tokens.validate(token, sessionId());
        limitedToken = "BIOMETRIC_ONLY".equals(tokens.source(token, sessionId()));
    }

    public Optional<String> optionalCustomerId() {
        String h = request.getHeader("X-Customer-Token");
        if (h == null || h.isBlank() || session == null) return Optional.empty();
        return Optional.of(customerId());
    }

    /** The customer token must belong to this customer. */
    public void requireCustomer(String expectedCustomerId) {
        if (!customerId().equals(expectedCustomerId)) {
            throw Errors.forbidden("This customer token does not cover that customer.");
        }
    }

    /** How the customer was verified for this token (see {@link CustomerTokens}). */
    public String customerAuth() {
        return tokens.source(request.getHeader("X-Customer-Token"), sessionId());
    }

    /**
     * Sensitive changes (PIN reset, unblock, reactivation, enrolment, signature) need the code and thumbprint check,
     * or a just-opened account. A card + PIN login is not enough: STEP_UP_REQUIRED.
     */
    public void requireStrongAuth() {
        String src = customerAuth();
        if (!"VERIFIED_IDENTITY".equals(src) && !"ONBOARDING".equals(src)) throw Errors.stepUpRequired();
    }

    public String idempotencyKey() {
        String k = request.getHeader("Idempotency-Key");
        if (k == null || k.isBlank()) throw Errors.idempotencyKeyRequired();
        if (k.length() > 100) throw Errors.validation("Idempotency-Key", "Idempotency-Key is too long");
        return k.trim();
    }

    public String requestId() { return RequestIds.of(request); }
}
