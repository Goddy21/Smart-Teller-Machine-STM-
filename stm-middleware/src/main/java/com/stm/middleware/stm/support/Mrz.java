package com.stm.middleware.stm.support;

import java.time.DateTimeException;
import java.time.LocalDate;

/** ICAO 9303 TD3 (passport) machine-readable zone: parsing with check-digit validation, plus a generator for demos/tests. */
public record Mrz(String documentNumber, String issuingState, String nationality, String surname,
                  String givenNames, LocalDate dateOfBirth, String sex, LocalDate expiryDate) {

    public static class MrzException extends RuntimeException {
        public MrzException(String message) { super(message); }
    }

    private static final int[] WEIGHTS = {7, 3, 1};

    public static int checkDigit(String data) {
        int sum = 0;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            int v;
            if (c >= '0' && c <= '9') v = c - '0';
            else if (c >= 'A' && c <= 'Z') v = c - 'A' + 10;
            else if (c == '<') v = 0;
            else throw new MrzException("Invalid MRZ character");
            sum += v * WEIGHTS[i % 3];
        }
        return sum % 10;
    }

    public static Mrz parse(String line1, String line2) {
        return parse(line1, line2, LocalDate.now());
    }

    public static Mrz parse(String line1, String line2, LocalDate today) {
        String l1 = clean(line1);
        String l2 = clean(line2);
        if (!l1.matches("[A-Z0-9<]{44}") || !l2.matches("[A-Z0-9<]{44}")) {
            throw new MrzException("MRZ must be two lines of 44 characters");
        }
        if (l1.charAt(0) != 'P') throw new MrzException("Not a passport MRZ");

        String docNo = l2.substring(0, 9);
        expect(l2.charAt(9), docNo, "document number");
        String dob = l2.substring(13, 19);
        expect(l2.charAt(19), dob, "date of birth");
        String exp = l2.substring(21, 27);
        expect(l2.charAt(27), exp, "expiry date");
        String personal = l2.substring(28, 42);
        boolean personalEmpty = personal.chars().allMatch(c -> c == '<');
        if (!(personalEmpty && l2.charAt(42) == '<')) expect(l2.charAt(42), personal, "personal number");
        expect(l2.charAt(43), l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, 43), "composite");

        String names = l1.substring(5);
        int idx = names.indexOf("<<");
        if (idx <= 0) throw new MrzException("Cannot read name fields");
        String surname = names.substring(0, idx).replace('<', ' ').replaceAll("\\s+", " ").trim();
        String given = names.substring(idx + 2).replace('<', ' ').replaceAll("\\s+", " ").trim();
        if (surname.isEmpty()) throw new MrzException("Missing surname");

        char s = l2.charAt(20);
        return new Mrz(docNo.replace("<", ""), l1.substring(2, 5).replace("<", ""),
                l2.substring(10, 13).replace("<", ""), surname, given,
                parseDate(dob, true, today), (s == 'M' || s == 'F') ? String.valueOf(s) : "X",
                parseDate(exp, false, today));
    }

    /** Builds a valid TD3 MRZ (2 lines). Used by the demo personas and unit tests. */
    public static String[] generate(String docNo, String issuer, String nationality, String surname, String given,
                                    LocalDate dob, char sex, LocalDate expiry) {
        String names = surname.toUpperCase().replace(' ', '<') + "<<" + given.toUpperCase().replace(' ', '<');
        String line1 = pad("P<" + issuer + names, 44);
        String doc = pad(docNo.toUpperCase(), 9);
        String d = yymmdd(dob);
        String e = yymmdd(expiry);
        String partial = doc + checkDigit(doc) + nationality + d + checkDigit(d) + sex + e + checkDigit(e)
                + "<".repeat(14) + "<";
        String composite = partial.substring(0, 10) + partial.substring(13, 20) + partial.substring(21, 43);
        return new String[]{line1, partial + checkDigit(composite)};
    }

    private static String pad(String s, int len) {
        return (s + "<".repeat(len)).substring(0, len);
    }

    private static String yymmdd(LocalDate d) {
        return String.format("%02d%02d%02d", d.getYear() % 100, d.getMonthValue(), d.getDayOfMonth());
    }

    private static String clean(String line) {
        if (line == null) throw new MrzException("Missing MRZ line");
        return line.replaceAll("\\s", "").toUpperCase();
    }

    private static void expect(char given, String data, String what) {
        if (given < '0' || given > '9' || given - '0' != checkDigit(data)) {
            throw new MrzException("Check digit mismatch: " + what);
        }
    }

    private static LocalDate parseDate(String v, boolean birth, LocalDate today) {
        try {
            int yy = Integer.parseInt(v.substring(0, 2));
            int mm = Integer.parseInt(v.substring(2, 4));
            int dd = Integer.parseInt(v.substring(4, 6));
            int year = 2000 + yy;
            if (birth && year > today.getYear()) year -= 100;
            return LocalDate.of(year, mm, dd);
        } catch (NumberFormatException | DateTimeException e) {
            throw new MrzException("Invalid date in MRZ");
        }
    }
}
