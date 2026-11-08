package com.stm.middleware.signaling;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class WsTicketServiceTest {
    /** Mutable clock so expiry can be tested without sleeping. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-05T09:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void kioskTicketCarriesTerminalIdentity() {
        var svc = new WsTicketService(30, new TestClock());
        var t = svc.issueForKiosk(7L, "STM-NBO-01");
        assertEquals(30, t.expiresInSeconds());
        var g = svc.redeem(t.ticket()).orElseThrow();
        assertEquals("KIOSK", g.role());
        assertEquals("STM-NBO-01", g.name());
        assertEquals(Long.valueOf(7L), g.terminalId());
    }

    @Test
    void tellerTicketHasNoTerminal() {
        var svc = new WsTicketService(30, new TestClock());
        var g = svc.redeem(svc.issueForTeller("T1").ticket()).orElseThrow();
        assertEquals("TELLER", g.role());
        assertEquals("T1", g.name());
        assertNull(g.terminalId());
    }

    @Test
    void ticketIsSingleUse() {
        var svc = new WsTicketService(30, new TestClock());
        var t = svc.issueForTeller("T1");
        assertTrue(svc.redeem(t.ticket()).isPresent());
        assertTrue(svc.redeem(t.ticket()).isEmpty());
    }

    @Test
    void expiredTicketIsRejected() {
        var clock = new TestClock();
        var svc = new WsTicketService(30, clock);
        var t = svc.issueForTeller("T1");
        clock.now = clock.now.plus(Duration.ofSeconds(30));
        assertTrue(svc.redeem(t.ticket()).isEmpty());
    }

    @Test
    void unknownOrBlankTicketIsRejected() {
        var svc = new WsTicketService(30, new TestClock());
        assertTrue(svc.redeem("not-a-ticket").isEmpty());
        assertTrue(svc.redeem("").isEmpty());
        assertTrue(svc.redeem(null).isEmpty());
    }

    @Test
    void expiredTicketsArePurgedOnIssue() {
        var clock = new TestClock();
        var svc = new WsTicketService(30, clock);
        svc.issueForTeller("T1");
        svc.issueForTeller("T2");
        clock.now = clock.now.plusSeconds(31);
        svc.issueForTeller("T3");
        assertEquals(1, svc.outstanding());
    }
}
