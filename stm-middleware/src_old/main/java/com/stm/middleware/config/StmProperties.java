package com.stm.middleware.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param corsAllowedOrigins     browser origins allowed to call the API and open the signaling WebSocket
 *                               (e.g. the Vite dev server). Empty = same-origin only.
 * @param wsTicketTtlSeconds     lifetime of a one-time WebSocket ticket (default 30 s).
 * @param allowTellerKeyInQuery  legacy teller sign-in with ?key=&lt;portal key&gt; on the WebSocket URL.
 *                               Keep false in production: query strings end up in proxy and access logs.
 */
@ConfigurationProperties(prefix = "stm")
public record StmProperties(
        String provisioningSecret,
        String portalApiKey,
        boolean requireClientCert,
        int lowCardStock,
        List<String> corsAllowedOrigins,
        int wsTicketTtlSeconds,
        boolean allowTellerKeyInQuery) {

    /** Configured origins without blanks (an empty env var binds as [""]). */
    public List<String> allowedOrigins() {
        return corsAllowedOrigins == null ? List.of()
                : corsAllowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public int ticketTtlSeconds() {
        return wsTicketTtlSeconds > 0 ? wsTicketTtlSeconds : 30;
    }
}
