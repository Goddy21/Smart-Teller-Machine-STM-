package com.stm.middleware.stm.core;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;

/** Resource ids in the spec's style (ses_01JC8Z4K2, app_7Q2M ...) and secrets. */
public final class Ids {
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final char[] ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789".toCharArray();

    private Ids() {}

    /** prefix_ + 12 Crockford base-32 characters (60 random bits). */
    public static String next(String prefix) {
        StringBuilder b = new StringBuilder(prefix).append('_');
        for (int i = 0; i < 12; i++) b.append(CROCKFORD[RNG.nextInt(32)]);
        return b.toString();
    }

    /** A bearer secret: 32 random bytes, URL-safe base64. Only its SHA-256 is stored. */
    public static String secret(String prefix) {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Core banking style reference: FT + yy + day of year + 5 characters, e.g. FT26310ABCDE. */
    public static String coreRef() {
        LocalDate d = LocalDate.now();
        StringBuilder b = new StringBuilder(String.format("FT%02d%03d", d.getYear() % 100, d.getDayOfYear()));
        for (int i = 0; i < 5; i++) b.append(ALNUM[RNG.nextInt(ALNUM.length)]);
        return b.toString();
    }

    public static String digits(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(RNG.nextInt(10));
        return b.toString();
    }
}
