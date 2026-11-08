package com.stm.middleware.stm.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money on the wire: the amount is a decimal string, never a float. {"amount":"12000.00","currency":"KES"} */
public record Money(String amount, String currency) {

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), currency);
    }

    public static Money kes(BigDecimal amount) {
        return of(amount, "KES");
    }

    public static Money kes(long amount) {
        return of(BigDecimal.valueOf(amount), "KES");
    }

    /** The amount as a number; throws VALIDATION_ERROR for anything that is not a plain decimal. */
    public BigDecimal value() {
        try {
            BigDecimal v = new BigDecimal(amount);
            if (v.scale() > 2) throw new NumberFormatException();
            return v;
        } catch (RuntimeException e) {
            throw Errors.validation("amount", "amount must be a decimal string with at most 2 decimals");
        }
    }

    public static String display(BigDecimal v, String currency) {
        return currency + " " + String.format("%,.2f", v);
    }
}
