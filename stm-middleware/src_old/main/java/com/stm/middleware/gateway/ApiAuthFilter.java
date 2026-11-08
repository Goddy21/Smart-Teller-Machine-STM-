package com.stm.middleware.gateway;

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
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates kiosk calls (Bearer terminal token, optionally bound to the mTLS client-cert CN).
 * Portal calls are handled by auth.PortalAuthFilter. NOT a Spring bean on purpose: it is created inside SecurityConfig
 * so it is not also auto-registered as a servlet filter.
 */
public class ApiAuthFilter extends OncePerRequestFilter {
    private static final Pattern CN = Pattern.compile("(?:^|,)\\s*CN=([^,]+)");

    private final TerminalService terminals;
    private final StmProperties props;

    public ApiAuthFilter(TerminalService terminals, StmProperties props) {
        this.terminals = terminals;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !p.startsWith("/api/v1/kiosk/");   // portal requests: PortalAuthFilter
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = bearer(req);
        if (token != null) {
            Optional<TerminalPrincipal> p = terminals.authenticate(token);
            if (p.isPresent() && certMatches(req, p.get())) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(p.get(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_KIOSK"))));
            }
        }
        chain.doFilter(req, res);
    }

    private static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : null;
    }

    /** When mTLS is enforced, the client certificate CN must equal the terminal code of the token. */
    private boolean certMatches(HttpServletRequest req, TerminalPrincipal p) {
        if (!props.requireClientCert()) return true;
        X509Certificate[] certs = (X509Certificate[]) req.getAttribute("jakarta.servlet.request.X509Certificate");
        if (certs == null || certs.length == 0) return false;
        Matcher m = CN.matcher(certs[0].getSubjectX500Principal().getName());
        return m.find() && m.group(1).trim().equals(p.terminalCode());
    }
}
