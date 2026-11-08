package com.stm.middleware.gateway;

import com.stm.middleware.common.ApiException;
import com.stm.middleware.common.Hashing;
import com.stm.middleware.config.StmProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/terminals")
public class TerminalController {
    private final TerminalService service;
    private final StmProperties props;

    public TerminalController(TerminalService service, StmProperties props) {
        this.service = service;
        this.props = props;
    }

    public record RegisterRequest(@NotBlank @Size(max = 50) String terminalCode,
                                  @NotBlank @Size(max = 20) String branchCode) {}

    /** Provisioning endpoint, used once per kiosk by the installer. Returns the terminal token one time only. */
    @PostMapping("/register")
    public ResponseEntity<TerminalService.Registration> register(
            @RequestHeader("X-Provisioning-Secret") String secret,
            @Valid @RequestBody RegisterRequest req) {
        if (!Hashing.constantTimeEquals(secret, props.provisioningSecret())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_PROVISIONING_SECRET", "Invalid provisioning secret");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(req.terminalCode(), req.branchCode()));
    }
}
