package com.stm.middleware;

import com.stm.middleware.journey.CardNames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CardNamesTest {
    @Test
    void offersFullAndInitialsFormsLikeTheKioskMockup() {
        assertEquals(List.of("AMARA N. OKAFOR", "A. N. OKAFOR"), CardNames.options("AMARA N", "OKAFOR"));
    }

    @Test
    void singleGivenName() {
        assertEquals(List.of("WANJIKU KAMAU", "W. KAMAU"), CardNames.options("WANJIKU", "KAMAU"));
    }

    @Test
    void neverExceedsEmbossingLimitAndStripsAccents() {
        for (String n : CardNames.options("MARIA CHRISTINA ELIZABETH", "ZAKHAROVA-LONGNAME")) {
            assertTrue(n.length() <= CardNames.MAX_LENGTH, n);
        }
        assertEquals(List.of("RENE DUPONT", "R. DUPONT"), CardNames.options("René", "Dupont"));
    }
}
