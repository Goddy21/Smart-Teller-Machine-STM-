package com.stm.middleware.gateway;

import com.stm.middleware.auth.PortalAuthFilter;
import com.stm.middleware.auth.PortalAuthProperties;
import com.stm.middleware.auth.PortalAuthService;
import com.stm.middleware.config.StmProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {
    private final TerminalService terminals;
    private final StmProperties props;
    private final PortalAuthService portalAuth;
    private final PortalAuthProperties portalAuthProps;

    public SecurityConfig(TerminalService terminals, StmProperties props, PortalAuthService portalAuth,
                          PortalAuthProperties portalAuthProps) {
        this.terminals = terminals;
        this.props = props;
        this.portalAuth = portalAuth;
        this.portalAuthProps = portalAuthProps;
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
                .requestMatchers("/api/v1/kiosk/**").hasRole("KIOSK")
                // ---- portal sign-in
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/auth/**").authenticated()
                // ---- portal, by role. TELLER and SUPERVISOR decide; SUPERVISOR controls kiosks;
                //      AUDITOR reads the audit trail; ADMIN manages accounts. Everyone signed in can read the rest.
                .requestMatchers("/api/v1/portal/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/portal/sessions/*/decision").hasAnyRole("TELLER", "SUPERVISOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/portal/signaling/ticket").hasAnyRole("TELLER", "SUPERVISOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/portal/terminals/*/commands").hasAnyRole("SUPERVISOR", "ADMIN")
                .requestMatchers("/api/v1/portal/audit/**").hasAnyRole("SUPERVISOR", "AUDITOR", "ADMIN")
                .requestMatchers("/api/v1/portal/**").hasRole("PORTAL")
                .anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(new ApiAuthFilter(terminals, props), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new PortalAuthFilter(portalAuth, portalAuthProps, props), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Lets a browser UI on another origin (e.g. Vite on http://localhost:5173) call the API.
     * Origins come from stm.cors-allowed-origins; empty = no cross-origin access (same-origin deployment).
     * Preflight (OPTIONS) requests are answered here, before authentication.
     * No cookies are used (Bearer / X-Portal-Key headers), so credentials stay disabled.
     */
    CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = props.allowedOrigins();
        if (origins.isEmpty()) return source;
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOriginPatterns(origins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Portal-Key",
                "X-Provisioning-Secret", "Accept", "Last-Event-ID"));
        c.setExposedHeaders(List.of("X-Agreement-Version"));
        c.setAllowCredentials(false);
        c.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }
}
