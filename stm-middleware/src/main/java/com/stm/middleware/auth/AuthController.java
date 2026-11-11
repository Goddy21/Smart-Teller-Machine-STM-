package com.stm.middleware.auth;

import com.stm.middleware.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portal sign-in. POST /login is open; everything else needs "Authorization: Bearer <session token>".
 * The token is returned once, at sign-in. The portal keeps it in sessionStorage (gone when the tab closes).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 128) String password) {}

    public record PasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(max = 128) String newPassword) {}

    public record OtpRequest(@NotBlank @Size(max = 100) String challenge, @NotBlank @Size(max = 12) String code) {}

    public record ResendRequest(@NotBlank @Size(max = 100) String challenge) {}

    /**
     * Either signed in (token, expiresAt, user) or, when the account has an email address, otpRequired with a challenge:
     * the portal then asks for the emailed code and calls POST /login/otp.
     */
    public record LoginResponse(String token, java.time.OffsetDateTime expiresAt, PortalUser user, boolean otpRequired,
                                String challenge, String maskedEmail, Integer expiresInSec, Integer resendAfterSec) {
        static LoginResponse of(PortalAuthService.Login l) {
            var o = l.otp();
            return o == null ? new LoginResponse(l.token(), l.expiresAt(), l.user(), false, null, null, null, null)
                    : new LoginResponse(null, null, null, true, o.challenge(), o.maskedEmail(), o.expiresInSec(), o.resendAfterSec());
        }
    }

    private final PortalAuthService auth;

    public AuthController(PortalAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest r, HttpServletRequest req) {
        return LoginResponse.of(auth.login(r.username(), r.password(), req.getRemoteAddr(), req.getHeader("User-Agent")));
    }

    /** Second step: the 6-digit code from the email. */
    @PostMapping("/login/otp")
    public LoginResponse otp(@Valid @RequestBody OtpRequest r, HttpServletRequest req) {
        return LoginResponse.of(auth.verifyOtp(r.challenge(), r.code(), req.getRemoteAddr(), req.getHeader("User-Agent")));
    }

    /** A new code for the same sign-in: at most 3, one a minute. */
    @PostMapping("/login/resend")
    public LoginResponse resend(@Valid @RequestBody ResendRequest r, HttpServletRequest req) {
        return LoginResponse.of(new PortalAuthService.Login(null, null, null, auth.resendOtp(r.challenge(), req.getRemoteAddr())));
    }

    @GetMapping("/me")
    public PortalUser me(@AuthenticationPrincipal PortalUser user) {
        return require(user);
    }

    @PostMapping("/logout")
    public void logout(@AuthenticationPrincipal PortalUser user, HttpServletRequest req) {
        auth.logout(PortalAuthFilter.bearer(req), user);
    }

    /** Also used for the forced change at first sign-in. Signs the user out on every other device. */
    @PostMapping("/password")
    public PortalUser changePassword(@AuthenticationPrincipal PortalUser user, @Valid @RequestBody PasswordRequest r,
                                     HttpServletRequest req) {
        return auth.changePassword(require(user), PortalAuthFilter.bearer(req), r.currentPassword(), r.newPassword());
    }

    private static PortalUser require(PortalUser user) {
        if (user == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Not signed in");
        return user;
    }
}
