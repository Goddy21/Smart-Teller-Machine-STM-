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
                             @Size(max = 200) String email, @NotNull PortalUser.Role role, @Size(max = 20) String branchCode, List<String> languages,
                             Boolean sendEmail) {}

    /** sendEmail: email the temporary password to the person instead of showing it to the administrator. */
    public record ResetRequest(Boolean sendEmail) {}

    /** Null fields are left unchanged. branchCode "" clears the branch; email "" removes the address. */
    public record UpdateUser(@Size(max = 100) String displayName, @Size(max = 200) String email, PortalUser.Role role, @Size(max = 20) String branchCode,
                             List<String> languages, String status) {}

    private final PortalAuthService auth;
    private final com.stm.middleware.common.EmailService email;

    public UserAdminController(PortalAuthService auth, com.stm.middleware.common.EmailService email) {
        this.auth = auth;
        this.email = email;
    }

    /** The last emails the middleware sent or simulated. Bodies (with codes) are shown only for simulated mail. */
    @GetMapping("/emails")
    public Map<String, Object> emails(@RequestParam(required = false) Integer page, @RequestParam(defaultValue = "25") int size) {
        if (page == null) return Map.of("live", email.live(), "emails", email.recent(100));
        var pg = email.page(page, size);
        return Map.of("live", email.live(), "emails", pg.items(), "total", pg.total(), "page", pg.page(), "size", pg.size(), "pages", pg.pages());
    }

    @GetMapping
    public List<PortalAuthService.UserRow> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return auth.listUsers(includeArchived);
    }

    /** Never-used accounts are deleted; used ones are archived (kept for the audit trail, cannot sign in). */
    @DeleteMapping("/{username}")
    public PortalAuthService.Removed remove(@AuthenticationPrincipal PortalUser admin, @PathVariable String username) {
        return auth.removeUser(admin, username);
    }

    @PostMapping("/{username}/sign-out")
    public Map<String, Integer> signOut(@AuthenticationPrincipal PortalUser admin, @PathVariable String username) {
        return Map.of("sessionsEnded", auth.signOutEverywhere(admin, username));
    }

    @GetMapping("/{username}/activity")
    public List<PortalAuthService.Activity> activity(@PathVariable String username, @RequestParam(defaultValue = "100") int limit) {
        return auth.activity(username, limit);
    }

    /** Returns the temporary password ONCE. Hand it to the person; they must change it at first sign-in. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PortalAuthService.Created create(@AuthenticationPrincipal PortalUser admin, @Valid @RequestBody CreateUser r) {
        return auth.createUser(admin, r.username(), r.displayName(), r.email(), r.role(), r.branchCode(), r.languages(),
                Boolean.TRUE.equals(r.sendEmail()));
    }

    @PutMapping("/{username}")
    public PortalAuthService.UserRow update(@AuthenticationPrincipal PortalUser admin, @PathVariable String username,
                                            @Valid @RequestBody UpdateUser r) {
        return auth.updateUser(admin, username, r.displayName(), r.email(), r.role(), r.branchCode(), r.languages(), r.status());
    }

    /** Body optional: {"sendEmail": true} emails the new temporary password instead of returning it. */
    @PostMapping("/{username}/reset-password")
    public PortalAuthService.Created reset(@AuthenticationPrincipal PortalUser admin, @PathVariable String username,
                                           @RequestBody(required = false) ResetRequest r) {
        return auth.resetPassword(admin, username, r != null && Boolean.TRUE.equals(r.sendEmail()));
    }
}
