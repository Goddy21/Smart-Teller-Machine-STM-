package com.stm.middleware.journey;

import com.stm.middleware.auth.PortalUser;
import com.stm.middleware.gateway.TerminalService;
import com.stm.middleware.journey.SessionDtos.DecisionRequest;
import com.stm.middleware.journey.SessionDtos.PortalSessionView;
import com.stm.middleware.signaling.SignalingHandler;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Teller console / compliance API for kiosk sessions. */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalSessionController {
    private final SessionService service;
    private final SessionRepository sessions;
    private final TerminalService terminals;
    private final SignalingHandler signaling;

    public PortalSessionController(SessionService service, SessionRepository sessions,
                                   TerminalService terminals, SignalingHandler signaling) {
        this.service = service;
        this.sessions = sessions;
        this.terminals = terminals;
        this.signaling = signaling;
    }

    /** e.g. ?status=IN_PROGRESS&escalated=true shows customers waiting for a teller. */
    @GetMapping("/sessions")
    public List<PortalSessionView> list(@RequestParam(required = false) String status,
                                        @RequestParam(required = false) Boolean escalated) {
        return service.list(status, escalated).stream().map(service::portalView).toList();
    }

    @GetMapping("/sessions/{id}")
    public PortalSessionView get(@PathVariable UUID id) {
        return service.portalView(service.getForPortal(id));
    }

    /** Teller inspects a scanned document or capture. Every read is audited. */
    @GetMapping("/sessions/{id}/documents/{docId}")
    public ResponseEntity<byte[]> document(@AuthenticationPrincipal PortalUser user, @PathVariable UUID id,
                                           @PathVariable UUID docId, @RequestParam(required = false) String viewer) {
        DocumentRepository.DocContent c = service.documentForPortal(id, docId, actor(user, viewer));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(c.mimeType())).body(c.content());
    }

    /** APPROVE_IDENTITY (biometric override), APPROVE_REVIEW (high-risk clearance) or REJECT. Pushes SESSION_UPDATED to the kiosk. */
    @PostMapping("/sessions/{id}/decision")
    public PortalSessionView decide(@AuthenticationPrincipal PortalUser user, @PathVariable UUID id,
                                    @Valid @RequestBody DecisionRequest r) {
        KioskSession s = service.decide(id, r.decision(), actor(user, r.tellerId()), r.notes());
        terminals.codeById(s.terminalId()).ifPresent(code -> signaling.push(code, "SESSION_UPDATED",
                Map.of("sessionId", id.toString(), "step", s.step().name(), "status", s.status())));
        return service.portalView(s);
    }

    /** Cards that could not be dispensed and must be handed over at a branch. */
    @GetMapping("/card-fulfilments")
    public List<SessionRepository.Fulfilment> fulfilments(@RequestParam(defaultValue = "PENDING_COLLECTION") String status) {
        return sessions.listFulfilments(status);
    }

    /**
     * Who acted, for the audit journal: always the signed-in user. Only the legacy shared key (scripts) may name
     * someone in the request, because it has no user of its own.
     */
    static String actor(PortalUser user, String claimed) {
        if (user != null && !user.service()) return user.username();
        return claimed == null || claimed.isBlank() ? "portal-key" : claimed.trim();
    }
}
