package com.stm.middleware.signaling;

import com.stm.middleware.common.ApiException;
import com.stm.middleware.common.Hashing;
import com.stm.middleware.config.StmProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, single-use tickets for the signaling WebSocket.
 *
 * Browsers cannot send an Authorization header on a WebSocket upgrade, so the client first asks for a ticket over
 * normal authenticated HTTP, then connects to /ws/signaling?ticket=... within the TTL. The ticket is consumed on
 * first use. Only its SHA-256 hash is kept, in memory (same single-instance limit as the call queue).
 */
@Service
public class WsTicketService {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_OUTSTANDING = 10_000;

    /** Who the ticket was issued to. terminalId is null for tellers. */
    public record Grant(String role, String name, Long terminalId) {}

    public record Ticket(String ticket, int expiresInSeconds) {}

    private record Entry(Grant grant, Instant expiresAt) {}

    private final Map<String, Entry> tickets = new ConcurrentHashMap<>();
    private final int ttlSeconds;
    private final Clock clock;

    @Autowired
    public WsTicketService(StmProperties props) {
        this(props.ticketTtlSeconds(), Clock.systemUTC());
    }

    WsTicketService(int ttlSeconds, Clock clock) {
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    public Ticket issueForKiosk(long terminalId, String terminalCode) {
        return issue(new Grant("KIOSK", terminalCode, terminalId));
    }

    public Ticket issueForTeller(String tellerId) {
        return issue(new Grant("TELLER", tellerId, null));
    }

    /** Returns the grant and burns the ticket. Empty if unknown, already used or expired. */
    public Optional<Grant> redeem(String ticket) {
        if (ticket == null || ticket.isBlank()) return Optional.empty();
        Entry e = tickets.remove(Hashing.sha256Hex(ticket));
        if (e == null || !clock.instant().isBefore(e.expiresAt())) return Optional.empty();
        return Optional.of(e.grant());
    }

    int outstanding() {
        return tickets.size();
    }

    private Ticket issue(Grant grant) {
        Instant now = clock.instant();
        tickets.values().removeIf(e -> !now.isBefore(e.expiresAt()));
        if (tickets.size() >= MAX_OUTSTANDING) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_TICKETS", "Too many outstanding WebSocket tickets");
        }
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tickets.put(Hashing.sha256Hex(ticket), new Entry(grant, now.plusSeconds(ttlSeconds)));
        return new Ticket(ticket, ttlSeconds);
    }
}
