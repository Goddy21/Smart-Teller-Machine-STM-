package com.stm.middleware.stm.onboarding;

import com.stm.middleware.stm.core.Errors;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The fixed choices on the kiosk's "Your details" pages, with the codes stored and sent to core banking. */
public final class KycOptions {
    private KycOptions() {}

    public record Option(String code, String label, String labelSw) {}

    public static final List<Option> SOURCE_OF_FUNDS = List.of(
            new Option("SALARY", "Salary or wages", "Mshahara au ujira"),
            new Option("BUSINESS", "Business income", "Mapato ya biashara"),
            new Option("FARMING", "Farming", "Kilimo"),
            new Option("REMITTANCES", "Money from family or abroad", "Pesa kutoka kwa familia au ng'ambo"),
            new Option("PENSION", "Pension", "Pensheni"),
            new Option("SAVINGS_INVESTMENTS", "Savings or investments", "Akiba au uwekezaji"),
            new Option("OTHER", "Other", "Nyingine"));

    public static final List<Option> MONTHLY_INCOME = List.of(
            new Option("UP_TO_10K", "Up to KES 10,000", "Hadi KES 10,000"),
            new Option("10K_50K", "KES 10,001 – 50,000", "KES 10,001 – 50,000"),
            new Option("50K_100K", "KES 50,001 – 100,000", "KES 50,001 – 100,000"),
            new Option("100K_500K", "KES 100,001 – 500,000", "KES 100,001 – 500,000"),
            new Option("OVER_500K", "Over KES 500,000", "Zaidi ya KES 500,000"));

    /** Suggestions only: the next-of-kin relationship is free text on the kiosk ("e.g. Sister"). */
    public static final List<Option> RELATIONSHIPS = List.of(
            new Option("SPOUSE", "Spouse", "Mwenzi"), new Option("PARENT", "Parent", "Mzazi"),
            new Option("CHILD", "Child", "Mtoto"), new Option("SIBLING", "Brother or sister", "Ndugu"),
            new Option("RELATIVE", "Other relative", "Jamaa mwingine"), new Option("FRIEND", "Friend", "Rafiki"));

    /** A code, or one of the labels as the kiosk shows them; free text from older kiosk builds is kept as written. */
    static String sourceOfFunds(String value) {
        String v = value.trim();
        if (v.isEmpty()) return null;
        for (Option o : SOURCE_OF_FUNDS) {
            if (o.code().equalsIgnoreCase(v) || o.label().equalsIgnoreCase(v)) return o.code();
        }
        return v.length() > 80 ? v.substring(0, 80) : v;
    }

    private static final java.util.regex.Pattern AMOUNT =
            java.util.regex.Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kKmM](?![a-zA-Z]))?");
    private static final long[] BAND_TOP = {10_000, 50_000, 100_000, 500_000};

    /**
     * One of the bands. Accepts the code or label, and also whatever form a kiosk build uses for the same band
     * ("KES 50,001 - 100,000", "50001-100000", "KES_50001_100000", "50k-100k", "OVER_500000", "500000+"),
     * by reading the amounts in it. A single amount (an actual monthly income) is put in the band that contains it.
     */
    static String monthlyIncome(String value) {
        String v = value.trim();
        if (v.isEmpty()) return null;
        String plain = v.replace('-', '\u2013').toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (Option o : MONTHLY_INCOME) {
            if (o.code().equalsIgnoreCase(v) || o.label().toLowerCase(Locale.ROOT).equals(plain)
                    || o.labelSw().toLowerCase(Locale.ROOT).equals(plain)) return o.code();
        }
        List<Long> amounts = new java.util.ArrayList<>();
        java.util.regex.Matcher m = AMOUNT.matcher(v);
        while (m.find()) {
            double n = Double.parseDouble(m.group(1).replace(",", ""));
            String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            amounts.add(Math.round(n * (unit.equals("k") ? 1_000 : unit.equals("m") ? 1_000_000 : 1)));
        }
        if (!amounts.isEmpty()) {
            String lower = v.toLowerCase(Locale.ROOT);
            boolean over = lower.contains("over") || lower.contains("above") || lower.contains("more")
                    || lower.contains("zaidi") || v.endsWith("+");
            long top = amounts.stream().mapToLong(Long::longValue).max().getAsLong();
            if (over && amounts.size() == 1) top = top + 1;          // "over 500,000" lies above that amount
            if (top > 0) {
                for (int i = 0; i < BAND_TOP.length; i++) if (top <= BAND_TOP[i]) return MONTHLY_INCOME.get(i).code();
                return MONTHLY_INCOME.get(MONTHLY_INCOME.size() - 1).code();
            }
        }
        throw Errors.validation("monthlyIncome", "Received \"" + (v.length() > 40 ? v.substring(0, 40) : v)
                + "\". Use one of " + MONTHLY_INCOME.stream().map(Option::code).toList() + " (GET /stm/v1/onboarding/kyc-options)");
    }

    public static String label(List<Option> list, String code) {
        return list.stream().filter(o -> o.code().equals(code)).map(Option::label).findFirst().orElse(code);
    }

    static Map<String, Object> asMap(Option o) {
        return Map.of("code", o.code(), "label", o.label(), "labelSw", o.labelSw());
    }
}
