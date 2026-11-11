package com.stm.middleware.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stm.middleware.auth.PortalAuthFilter;
import com.stm.middleware.auth.PortalAuthProperties;
import com.stm.middleware.auth.PortalAuthService;
import com.stm.middleware.config.StmProperties;
import com.stm.middleware.stm.core.DeviceAuthFilter;
import com.stm.middleware.stm.core.DeviceTokenService;
import com.stm.middleware.stm.core.ErrorBodies;
import com.stm.middleware.stm.core.Errors;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {
    private final StmProperties props;
    private final PortalAuthService portalAuth;
    private final PortalAuthProperties portalAuthProps;
    private final DeviceTokenService deviceTokens;
    private final ObjectMapper mapper;

    public SecurityConfig(StmProperties props, PortalAuthService portalAuth, PortalAuthProperties portalAuthProps,
                          DeviceTokenService deviceTokens, ObjectMapper mapper) {
        this.props = props;
        this.portalAuth = portalAuth;
        this.portalAuthProps = portalAuthProps;
        this.deviceTokens = deviceTokens;
        this.mapper = mapper;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .cors(c -> c.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health/**", "/api/v1/terminals/register",
                                 "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // WebSocket handshake is authenticated by SignalingHandshakeInterceptor
                .requestMatchers("/ws/**").permitAll()
                // ---- Smart Teller Gateway (kiosk). D-1 checks its own credential (mTLS CN or provisioning token).
                .requestMatchers(HttpMethod.POST, "/stm/v1/auth/device/token").permitAll()
                .requestMatchers("/stm/v1/**").hasRole("DEVICE")
                // ---- portal sign-in
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/login/otp", "/api/v1/auth/login/resend").permitAll()
                .requestMatchers("/api/v1/auth/**").authenticated()
                // ---- portal, by role. TELLER and SUPERVISOR decide; SUPERVISOR and ADMIN control kiosks;
                //      AUDITOR reads the audit trail; ADMIN manages accounts. Everyone signed in can read the rest.
                .requestMatchers("/api/v1/portal/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/portal/applications/*/decision").hasAnyRole("TELLER", "SUPERVISOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/portal/signaling/ticket").hasAnyRole("TELLER", "SUPERVISOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/portal/terminals/*/commands").hasAnyRole("SUPERVISOR", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/portal/fx-rates/**", "/api/v1/portal/simulator").hasAnyRole("SUPERVISOR", "ADMIN")
                .requestMatchers("/api/v1/portal/audit/**", "/api/v1/portal/reconciliation/**",
                                 "/api/v1/portal/device-journal/**").hasAnyRole("SUPERVISOR", "AUDITOR", "ADMIN")
                .requestMatchers("/api/v1/portal/**").hasRole("PORTAL")
                .anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint()).accessDeniedHandler(accessDenied()))
            .addFilterBefore(new DeviceAuthFilter(deviceTokens, props), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new PortalAuthFilter(portalAuth, portalAuthProps, props), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Gateway callers get the spec error body; the portal gets a bare 401 (its client maps it). */
    private AuthenticationEntryPoint entryPoint() {
        return (req, res, ex) -> {
            if (req.getRequestURI().startsWith("/stm/")) ErrorBodies.write(req, res, Errors.deviceUnauthorized(), mapper);
            else res.sendError(HttpStatus.UNAUTHORIZED.value());
        };
    }

    private AccessDeniedHandler accessDenied() {
        return (req, res, ex) -> {
            if (req.getRequestURI().startsWith("/stm/")) {
                ErrorBodies.write(req, res, Errors.forbidden("This device may not make this call."), mapper);
            } else {
                res.sendError(HttpStatus.FORBIDDEN.value());
            }
        };
    }

    /**
     * Lets a browser UI on another origin (e.g. Vite on http://localhost:5173) call the API.
     * Origins come from stm.cors-allowed-origins; empty = no cross-origin access (same-origin deployment).
     * No cookies are used (Bearer / X-Portal-Key headers), so credentials stay disabled.
     */
    CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = props.allowedOrigins();
        if (origins.isEmpty()) return source;
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOriginPatterns(origins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Portal-Key",
                "X-Provisioning-Secret", "Accept", "Accept-Language", "Last-Event-ID", "X-Device-Id", "X-Session-Id",
                "X-Request-Id", "X-Customer-Token"));
        c.setExposedHeaders(List.of("X-Request-Id", "Idempotent-Replayed", "X-Pages"));
        c.setAllowCredentials(false);
        c.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/**", c);
        source.registerCorsConfiguration("/stm/**", c);
        return source;
    }
}
