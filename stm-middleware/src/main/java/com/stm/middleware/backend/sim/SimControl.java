package com.stm.middleware.backend.sim;

import com.stm.middleware.stm.core.Errors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Knobs for the simulated back ends: latency, and "core is down" to exercise the reversal paths.
 * The portal's Simulator panel flips these (demo profile only).
 */
@Component
public class SimControl {
    private final AtomicBoolean coreDown = new AtomicBoolean(false);
    private final AtomicInteger latencyMs;
    private final String fixedOtp;
    private final boolean prod;
    private final AtomicBoolean acceptAll;

    public SimControl(@Value("${stm.sim.latency-ms:0}") int latencyMs, @Value("${stm.sim.fixed-otp:}") String fixedOtp,
                      @Value("${stm.sim.accept-all:false}") boolean acceptAll, Environment env) {
        this.latencyMs = new AtomicInteger(Math.max(0, latencyMs));
        this.fixedOtp = fixedOtp == null || fixedOtp.isBlank() ? null : fixedOtp.trim();
        this.prod = env.acceptsProfiles(Profiles.of("prod"));
        this.acceptAll = new AtomicBoolean(acceptAll && !prod);
    }

    /**
     * TEST MODE (stm.sim.accept-all, the "Accept everything" switch on the portal's Simulator page): whatever the kiosk
     * sends is accepted. Any ID or passport number (an unknown one is added to the simulated registry from what was
     * scanned), any face, fingerprint, KRA PIN, signature image and one-time code; no screening hits, no SIM-swap
     * blocks, and an existing customer may open another account. Never on in the prod profile.
     */
    public boolean acceptAll() { return acceptAll.get(); }
    public void setAcceptAll(boolean on) { acceptAll.set(on && !prod); }

    /** Called at the start of every simulated core call. */
    public void core() {
        int ms = latencyMs.get();
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (coreDown.get()) throw Errors.coreUnavailable();
    }

    public boolean coreDown() { return coreDown.get(); }
    public void setCoreDown(boolean down) { coreDown.set(down); }
    public int latencyMs() { return latencyMs.get(); }
    public void setLatencyMs(int ms) { latencyMs.set(Math.max(0, Math.min(ms, 30_000))); }

    /** Demo profile: every OTP is this code (so scripts and testers can type it). null = random codes. */
    public String fixedOtp() { return fixedOtp; }
}
