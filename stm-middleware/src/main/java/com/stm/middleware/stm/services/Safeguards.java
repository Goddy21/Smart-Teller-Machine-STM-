package com.stm.middleware.stm.services;

import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.stm.core.Errors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Rules that protect a customer whose phone number was just changed: the new number receives every code, so for
 * stm.services.mobile-change-hold-hours the kiosk refuses PIN resets, unblocking, card orders, reactivation and a
 * second change (403 MOBILE_RECENTLY_CHANGED, the kiosk offers a teller).
 */
@Component
public class Safeguards {
    private final CoreBanking core;
    private final int holdHours;

    public Safeguards(CoreBanking core, @Value("${stm.services.mobile-change-hold-hours:24}") int holdHours) {
        this.core = core;
        this.holdHours = Math.max(0, holdHours);
    }

    public int holdHours() { return holdHours; }

    /** Hours left of the hold after a phone-number change; 0 when there is none. */
    public long holdHoursLeft(String customerId) {
        Optional<OffsetDateTime> changed = core.mobileChangedAt(customerId);
        if (changed.isEmpty() || holdHours == 0) return 0;
        long minutes = Duration.between(OffsetDateTime.now(), changed.get().plusHours(holdHours)).toMinutes();
        return minutes <= 0 ? 0 : (minutes + 59) / 60;
    }

    public void requireSettledMobile(String customerId) {
        long left = holdHoursLeft(customerId);
        if (left > 0) throw Errors.mobileRecentlyChanged(left);
    }
}
