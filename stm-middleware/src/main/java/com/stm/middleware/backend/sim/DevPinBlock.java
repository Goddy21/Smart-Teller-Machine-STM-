package com.stm.middleware.backend.sim;

import com.stm.middleware.stm.core.Errors;

/**
 * SIMULATED HSM ONLY. Real kiosks send ISO 9564 format-4 PIN blocks encrypted by the PIN pad; the bank's HSM
 * translates them and nobody else ever sees the PIN. Without a PIN pad, the demo kiosk sends the clear ISO
 * "PIN field": '0', the PIN length, the digits, padded with F to 16 hex characters, e.g. PIN 2580 -> 042580FFFFFFFFFF.
 * Never accept this format outside the demo profile.
 */
public final class DevPinBlock {
    private DevPinBlock() {}

    public static String encode(String pin) {
        StringBuilder b = new StringBuilder("0").append(Integer.toHexString(pin.length()).toUpperCase()).append(pin);
        while (b.length() < 16) b.append('F');
        return b.toString();
    }

    public static String decode(String block) {
        if (block != null && block.matches("0[4-9A-C][0-9]{4,12}F*") && block.length() == 16) {
            int len = Integer.parseInt(block.substring(1, 2), 16);
            String digits = block.substring(2, 2 + len);
            if (digits.chars().allMatch(Character::isDigit) && block.substring(2 + len).chars().allMatch(c -> c == 'F')) {
                return digits;
            }
        }
        throw Errors.validation("pinBlock", "The simulated HSM accepts only development PIN blocks such as 042580FFFFFFFFFF");
    }

    /** null when the PIN is acceptable, else the rule it breaks. */
    public static String policyViolation(String pin) {
        if (pin.chars().distinct().count() == 1) return "REPEATED_DIGITS";
        boolean up = true, down = true;
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != 1) up = false;
            if (pin.charAt(i - 1) - pin.charAt(i) != 1) down = false;
        }
        if (up || down) return "SEQUENTIAL_DIGITS";
        return null;
    }
}
