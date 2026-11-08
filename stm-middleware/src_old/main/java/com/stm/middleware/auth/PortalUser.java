package com.stm.middleware.auth;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A signed-in portal user, set as the Spring Security principal on /api/v1/portal and /api/v1/auth requests.
 * {@code service} is true only for the legacy shared X-Portal-Key (scripts), which acts as an administrator.
 */
public record PortalUser(long id, String username, String displayName, Role role, String branchCode,
                         List<String> languages, boolean mustChangePassword, OffsetDateTime sessionExpiresAt,
                         boolean service) {

    public enum Role {
        TELLER, SUPERVISOR, AUDITOR, ADMIN;

        /** Spring authorities for this role. Everyone signed in also gets ROLE_PORTAL (read access). */
        public List<String> authorities() {
            return List.of("ROLE_PORTAL", "ROLE_" + name());
        }
    }

    static PortalUser sharedKey() {
        return new PortalUser(0, "portal-key", "Shared portal key", Role.ADMIN, null, List.of("en"), false, null, true);
    }
}
