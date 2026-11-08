package com.stm.middleware.stm.core;

import com.stm.middleware.stm.support.SimplePdf;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoneyAndIdsTest {
    @Test
    void moneyIsADecimalString() {
        assertEquals(new Money("12000.00", "KES"), Money.kes(new BigDecimal("12000")));
        assertEquals("12840.50", Money.kes(new BigDecimal("12840.5")).amount());
        assertEquals(0, new BigDecimal("47350.00").compareTo(new Money("47350.00", "KES").value()));
        assertThrows(StmException.class, () -> new Money("12.345", "KES").value());
        assertThrows(StmException.class, () -> new Money("abc", "KES").value());
    }

    @Test
    void ids() {
        assertTrue(Ids.next("ses").matches("ses_[0-9A-HJKMNP-TV-Z]{12}"));
        assertTrue(Ids.coreRef().matches("FT\\d{5}[A-Z0-9]{5}"));
        assertNotEquals(Ids.secret("cst_"), Ids.secret("cst_"));
    }

    @Test
    void pdfIsWellFormed() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 130; i++) lines.add("Line " + i + " (with brackets) and a • bullet");
        byte[] pdf = SimplePdf.render(lines, "footer");
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("%PDF-1.4"));
        assertTrue(s.contains("/Count 3"));
        assertTrue(s.trim().endsWith("%%EOF"));
        assertEquals(3, SimplePdf.pages(130));
    }
}
