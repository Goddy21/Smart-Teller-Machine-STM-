package com.stm.middleware.stm.support;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class MrzTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @Test
    void parsesTheIcaoSpecimenPassport() {
        Mrz m = Mrz.parse("P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
                "L898902C36UTO7408122F1204159ZE184226B<<<<<10", TODAY);
        assertEquals("L898902C3", m.documentNumber());
        assertEquals("ERIKSSON", m.surname());
        assertEquals("ANNA MARIA", m.givenNames());
        assertEquals(LocalDate.of(1974, 8, 12), m.dateOfBirth());
        assertEquals(LocalDate.of(2012, 4, 15), m.expiryDate());
        assertEquals("F", m.sex());
    }

    @Test
    void generatedMrzRoundTrips() {
        String[] g = Mrz.generate("A1234567", "KEN", "KEN", "OKAFOR", "AMARA N",
                LocalDate.of(1990, 3, 14), 'F', LocalDate.of(2031, 9, 2));
        Mrz m = Mrz.parse(g[0], g[1], TODAY);
        assertEquals("A1234567", m.documentNumber());
        assertEquals("OKAFOR", m.surname());
        assertEquals("AMARA N", m.givenNames());
        assertEquals(LocalDate.of(1990, 3, 14), m.dateOfBirth());
        assertEquals(LocalDate.of(2031, 9, 2), m.expiryDate());
    }

    @Test
    void anyCorruptedCharacterIsRejected() {
        String[] g = Mrz.generate("A1234567", "KEN", "KEN", "OKAFOR", "AMARA N",
                LocalDate.of(1990, 3, 14), 'F', LocalDate.of(2031, 9, 2));
        String bad = g[1].substring(0, 13) + "8" + g[1].substring(14);
        assertThrows(Mrz.MrzException.class, () -> Mrz.parse(g[0], bad, TODAY));
        assertThrows(Mrz.MrzException.class, () -> Mrz.parse("short", "short", TODAY));
        assertThrows(Mrz.MrzException.class, () -> Mrz.parse(null, g[1], TODAY));
    }
}
