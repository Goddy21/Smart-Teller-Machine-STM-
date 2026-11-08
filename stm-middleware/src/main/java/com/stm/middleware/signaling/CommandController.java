package com.stm.middleware.signaling;

import com.stm.middleware.audit.AuditService;
import com.stm.middleware.common.ApiException;
import com.stm.middleware.gateway.TerminalService;
import com.stm.middleware.telemetry.TelemetryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/portal/terminals")
public class CommandController {
    public enum Command { REBOOT, LOCK, UNLOCK, REFRESH_CONFIG }

    public record CommandRequest(@NotNull Command command) {}

    private final SignalingHandler handler;
    private final TerminalService terminals;
    private final AuditService audit;
    private final TelemetryService telemetry;

    public CommandController(SignalingHandler handler, TerminalService terminals, AuditService audit,
                             TelemetryService telemetry) {
        this.handler = handler;
        this.terminals = terminals;
        this.audit = audit;
        this.telemetry = telemetry;
    }

    /**
     * LOCK / UNLOCK are applied in the database first (D-2 refuses new sessions and D-7 reports out of service)
     * and pushed over the WebSocket as a best effort. REBOOT / REFRESH_CONFIG need an open WebSocket.
     */
    @PostMapping("/{terminalCode}/commands")
    public Map<String, Object> send(@PathVariable String terminalCode, @Valid @RequestBody CommandRequest req) {
        var terminal = terminals.findByCode(terminalCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TERMINAL_NOT_FOUND", "Unknown terminal"));
        Command cmd = req.command();
        if (cmd == Command.LOCK) terminals.setServiceMode(terminalCode, "LOCKED");
        if (cmd == Command.UNLOCK) terminals.setServiceMode(terminalCode, "IN_SERVICE");
        boolean pushed = handler.sendCommand(terminalCode, cmd.name());
        boolean persistent = cmd == Command.LOCK || cmd == Command.UNLOCK;
        if (!pushed && !persistent) {
            throw new ApiException(HttpStatus.CONFLICT, "TERMINAL_NOT_CONNECTED", "Terminal has no open WebSocket");
        }
        audit.record("REMOTE_COMMAND", terminalCode, terminal.terminalId(), Map.of("command", cmd.name()));
        if (persistent) telemetry.changed(terminalCode);
        return Map.of("applied", true, "pushed", pushed);
    }
}
