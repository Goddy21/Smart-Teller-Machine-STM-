package com.stm.middleware.backend.sim;

import com.stm.middleware.stm.core.Errors;
import org.springframework.beans.factory.annotation.Value;
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

    public SimControl(@Value("${stm.sim.latency-ms:0}") int latencyMs, @Value("${stm.sim.fixed-otp:}") String fixedOtp) {
        this.latencyMs = new AtomicInteger(Math.max(0, latencyMs));
        this.fixedOtp = fixedOtp == null || fixedOtp.isBlank() ? null : fixedOtp.trim();
    }

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
