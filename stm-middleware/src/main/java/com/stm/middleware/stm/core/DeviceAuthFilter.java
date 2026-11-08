package com.stm.middleware.stm.core;

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
 * Authenticates kiosk calls to the Gateway (/stm/v1): "Authorization: Bearer &lt;device token&gt;" plus
 * X-Device-Id, which must name the same kiosk (and match the mTLS client certificate CN when that is enforced).
 * Created by SecurityConfig, not a bean, so it is not also registered as a plain servlet filter.
 */
public class DeviceAuthFilter extends OncePerRequestFilter {
    private static final Pattern CN = Pattern.compile("(?:^|,)\\s*CN=([^,]+)");

    private final DeviceTokenService tokens;
    private final StmProperties props;

    public DeviceAuthFilter(DeviceTokenService tokens, StmProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !p.startsWith("/stm/v1/") || p.equals("/stm/v1/auth/device/token");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            Optional<DevicePrincipal> p = tokens.authenticate(h.substring(7).trim());
            String deviceId = req.getHeader("X-Device-Id");
            if (p.isPresent() && p.get().deviceId().equals(deviceId) && certMatches(req, p.get().deviceId(), props)) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        p.get(), null, List.of(new SimpleGrantedAuthority("ROLE_DEVICE"))));
            }
        }
        chain.doFilter(req, res);
    }

    /** When mTLS is enforced, the client certificate CN must be the device id. */
    public static boolean certMatches(HttpServletRequest req, String deviceId, StmProperties props) {
        if (!props.requireClientCert()) return true;
        return clientCertCn(req).map(deviceId::equals).orElse(false);
    }

    public static Optional<String> clientCertCn(HttpServletRequest req) {
        X509Certificate[] certs = (X509Certificate[]) req.getAttribute("jakarta.servlet.request.X509Certificate");
        if (certs == null || certs.length == 0) return Optional.empty();
        Matcher m = CN.matcher(certs[0].getSubjectX500Principal().getName());
        return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
    }
}
