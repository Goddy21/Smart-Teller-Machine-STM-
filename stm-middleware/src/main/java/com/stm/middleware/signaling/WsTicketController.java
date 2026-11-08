package com.stm.middleware.signaling;

import com.stm.middleware.auth.PortalUser;
import com.stm.middleware.stm.core.DevicePrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues one-time tickets for /ws/signaling?ticket=...  Valid for a few seconds, usable once.
 * Kiosk: authenticated by its device token (Gateway). Teller: by their portal session (the ticket carries their username).
 */
@RestController
public class WsTicketController {
    public record TellerTicketRequest(@Size(max = 50) @Pattern(regexp = "[A-Za-z0-9._@-]*") String tellerId) {}

    private final WsTicketService tickets;

    public WsTicketController(WsTicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/stm/v1/signaling/ticket")
    public WsTicketService.Ticket kiosk(@AuthenticationPrincipal DevicePrincipal p) {
        return tickets.issueForKiosk(p.terminalId(), p.deviceId());
    }

    @PostMapping("/api/v1/portal/signaling/ticket")
    public WsTicketService.Ticket teller(@AuthenticationPrincipal PortalUser user,
                                         @Valid @RequestBody(required = false) TellerTicketRequest r) {
        // Signed-in users get a ticket in their own name; only the shared script key may name a teller.
        String id = user != null && !user.service() ? user.username()
                : r == null || r.tellerId() == null || r.tellerId().isBlank() ? "teller" : r.tellerId();
        return tickets.issueForTeller(id);
    }
}
