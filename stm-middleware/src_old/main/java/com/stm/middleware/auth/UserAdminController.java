package com.stm.middleware.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Portal account administration (role ADMIN, enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/v1/portal/users")
public class UserAdminController {
    public record CreateUser(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 100) String displayName,
                             @NotNull PortalUser.Role role, @Size(max = 20) String branchCode, List<String> languages) {}

    /** Null fields are left unchanged. branchCode "" clears the branch. */
    public record UpdateUser(@Size(max = 100) String displayName, PortalUser.Role role, @Size(max = 20) String branchCode,
                             List<String> languages, String status) {}

    private final PortalAuthService auth;

    public UserAdminController(PortalAuthService auth) {
        this.auth = auth;
    }

    @GetMapping
    public List<PortalAuthService.UserRow> list() {
        return auth.listUsers();
    }

    /** Returns the temporary password ONCE. Hand it to the person; they must change it at first sign-in. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PortalAuthService.Created create(@AuthenticationPrincipal PortalUser admin, @Valid @RequestBody CreateUser r) {
        return auth.createUser(admin, r.username(), r.displayName(), r.role(), r.branchCode(), r.languages());
    }

    @PutMapping("/{username}")
    public PortalAuthService.UserRow update(@AuthenticationPrincipal PortalUser admin, @PathVariable String username,
                                            @Valid @RequestBody UpdateUser r) {
        return auth.updateUser(admin, username, r.displayName(), r.role(), r.branchCode(), r.languages(), r.status());
    }

    @PostMapping("/{username}/reset-password")
    public Map<String, String> reset(@AuthenticationPrincipal PortalUser admin, @PathVariable String username) {
        return Map.of("temporaryPassword", auth.resetPassword(admin, username));
    }
}
