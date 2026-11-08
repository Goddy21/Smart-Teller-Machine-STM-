package com.stm.middleware.telemetry;

import com.stm.middleware.gateway.TerminalPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
public class TelemetryController {
    private final TelemetryService service;

    public TelemetryController(TelemetryService service) {
        this.service = service;
    }

    /** Kiosk heartbeat: send every ~30s and on any hardware change. */
    @PostMapping("/api/v1/kiosk/telemetry")
    public void heartbeat(@AuthenticationPrincipal TerminalPrincipal p, @Valid @RequestBody TelemetryService.Heartbeat hb) {
        service.record(p, hb);
    }

    @GetMapping("/api/v1/portal/terminals")
    public List<TelemetryService.TerminalView> terminals() {
        return service.list();
    }

    /** Server-Sent Events stream of terminal updates for the portal's fleet dashboard. */
    @GetMapping(value = "/api/v1/portal/telemetry/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return service.subscribe();
    }
}
