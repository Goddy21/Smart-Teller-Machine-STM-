package com.stm.middleware.journey;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Embossing name choices, e.g. "AMARA N. OKAFOR" and "A. N. OKAFOR" (max 26 characters, ASCII upper-case). */
public final class CardNames {
    public static final int MAX_LENGTH = 26;

    private CardNames() {}

    public static List<String> options(String givenNames, String surname) {
        String sur = clean(surname);
        List<String> given = Arrays.stream(clean(givenNames).split(" ")).filter(s -> !s.isBlank()).toList();
        Set<String> all = new LinkedHashSet<>();
        if (given.isEmpty()) {
            all.add(sur);
        } else {
            StringBuilder first = new StringBuilder(given.get(0));
            StringBuilder initials = new StringBuilder();
            for (int i = 0; i < given.size(); i++) {
                if (i > 0) first.append(' ').append(given.get(i).charAt(0)).append('.');
                initials.append(given.get(i).charAt(0)).append(". ");
            }
            all.add(first + " " + sur);
            all.add(initials + sur);
        }
        List<String> fit = new ArrayList<>(all.stream().filter(n -> n.length() <= MAX_LENGTH).toList());
        if (fit.isEmpty()) {
            String f = given.isEmpty() ? "" : given.get(0).charAt(0) + ". ";
            fit.add((f + sur).substring(0, Math.min(MAX_LENGTH, (f + sur).length())).trim());
        }
        return fit;
    }

    static String clean(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase();
        return n.replaceAll("[^A-Z' -]", " ").replaceAll("\\s+", " ").trim();
    }
}
