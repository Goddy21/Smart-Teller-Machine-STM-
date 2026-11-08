package com.stm.middleware.auth;

import com.stm.middleware.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PasswordRulesTest {
    @Test
    void acceptsLongPasswordsWithLettersAndDigits() {
        assertDoesNotThrow(() -> PortalAuthService.checkPolicy("Nairobi2026x", "amara"));
    }

    @Test
    void rejectsShortOrSingleClassPasswords() {
        assertThrows(ApiException.class, () -> PortalAuthService.checkPolicy("Short1", "amara"));
        assertThrows(ApiException.class, () -> PortalAuthService.checkPolicy("onlylettersxx", "amara"));
        assertThrows(ApiException.class, () -> PortalAuthService.checkPolicy("1234567890123", "amara"));
        assertThrows(ApiException.class, () -> PortalAuthService.checkPolicy(null, "amara"));
    }

    @Test
    void rejectsPasswordsContainingTheUsername() {
        assertThrows(ApiException.class, () -> PortalAuthService.checkPolicy("Teller1-password9", "teller1"));
    }

    @Test
    void temporaryPasswordsPassThePolicyAndDiffer() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String p = PortalAuthService.temporaryPassword();
            assertEquals(14, p.length());
            assertFalse(p.matches(".*[0O1lI].*"), "no look-alike characters: " + p);
            assertDoesNotThrow(() -> PortalAuthService.checkPolicy(p, "someone"));
            seen.add(p);
        }
        assertEquals(200, seen.size());
    }
}
