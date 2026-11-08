package com.stm.middleware.auth;

import com.stm.middleware.common.Hashing;
import com.stm.middleware.config.StmProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates portal requests (/api/v1/portal/**, /api/v1/auth/**):
 *   "Authorization: Bearer <session token>" from POST /api/v1/auth/login, or
 *   the legacy shared "X-Portal-Key" when stm.portal-auth.allow-shared-key is true (scripts; never in production).
 * A user who must change their password only gets ROLE_PASSWORD_CHANGE, so every portal API refuses them until they do.
 * Not a Spring bean on purpose (created in SecurityConfig), so it is not also registered as a servlet filter.
 */
public class PortalAuthFilter extends OncePerRequestFilter {
    private static final List<SimpleGrantedAuthority> SHARED_KEY = List.of("ROLE_PORTAL", "ROLE_TELLER", "ROLE_SUPERVISOR",
            "ROLE_AUDITOR", "ROLE_ADMIN").stream().map(SimpleGrantedAuthority::new).toList();

    private final PortalAuthService auth;
    private final PortalAuthProperties authProps;
    private final StmProperties props;

    public PortalAuthFilter(PortalAuthService auth, PortalAuthProperties authProps, StmProperties props) {
        this.auth = auth;
        this.authProps = authProps;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !(p.startsWith("/api/v1/portal/") || p.startsWith("/api/v1/auth/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = bearer(req);
        if (token != null) {
            auth.authenticate(token).ifPresent(u -> {
                List<SimpleGrantedAuthority> roles = u.mustChangePassword()
                        ? List.of(new SimpleGrantedAuthority("ROLE_PASSWORD_CHANGE"))
                        : u.role().authorities().stream().map(SimpleGrantedAuthority::new).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, roles));
            });
        } else if (authProps.allowSharedKey()) {
            String key = req.getHeader("X-Portal-Key");
            if (key != null && Hashing.constantTimeEquals(key, props.portalApiKey())) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(PortalUser.sharedKey(), null, SHARED_KEY));
            }
        }
        chain.doFilter(req, res);
    }

    static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : null;
    }
}
