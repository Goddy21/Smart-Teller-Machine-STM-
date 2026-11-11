package com.stm.middleware.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * stm.portal-auth.*  Portal sign-in settings. Zero / missing values fall back to the defaults below.
 *
 * @param idleMinutes             a session ends after this long without a request (default 30)
 * @param absoluteHours           and in any case this long after sign-in (default 10, one working day)
 * @param maxFailedAttempts       wrong passwords in a row before the account locks (default 5)
 * @param lockMinutes             how long it stays locked (default 15)
 * @param allowSharedKey          also accept the old shared X-Portal-Key (scripts, demo). Never true in production.
 * @param bootstrapAdminPassword  password for the first "admin" account when the user table is empty. Blank = a random
 *                                one is generated and printed once in the log. The admin must change it at first sign-in.
 */
@ConfigurationProperties(prefix = "stm.portal-auth")
public record PortalAuthProperties(int idleMinutes, int absoluteHours, int maxFailedAttempts, int lockMinutes,
                                   boolean allowSharedKey, String bootstrapAdminPassword,
                                   Boolean emailOtp, int otpMinutes, int otpMaxAttempts) {
    public int idle() { return idleMinutes > 0 ? idleMinutes : 30; }
    public int absolute() { return absoluteHours > 0 ? absoluteHours : 10; }
    public int maxFailures() { return maxFailedAttempts > 0 ? maxFailedAttempts : 5; }
    public int lock() { return lockMinutes > 0 ? lockMinutes : 15; }
    public boolean otp() { return emailOtp == null || emailOtp; }
    public int otpMins() { return otpMinutes > 0 ? otpMinutes : 5; }
    public int otpTries() { return otpMaxAttempts > 0 ? otpMaxAttempts : 5; }
}
