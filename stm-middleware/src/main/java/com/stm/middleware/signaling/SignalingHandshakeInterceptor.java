package com.stm.middleware.signaling;

import com.stm.middleware.common.Hashing;
import com.stm.middleware.config.StmProperties;
import com.stm.middleware.stm.core.DevicePrincipal;
import com.stm.middleware.stm.core.DeviceTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

/**
 * Authenticates the WebSocket upgrade. In order of preference:
 *   1. Browser kiosk or teller: POST .../signaling/ticket, then /ws/signaling?ticket=<one-time ticket>
 *   2. Native kiosk client:     /ws/signaling?role=kiosk + "Authorization: Bearer <device token from POST /stm/v1/auth/device/token>"
 *   3. Legacy teller (dev only, stm.allow-teller-key-in-query): /ws/signaling?role=teller&tellerId=T1&key=<portal key>
 */
@Component
public class SignalingHandshakeInterceptor implements HandshakeInterceptor {
    private final DeviceTokenService deviceTokens;
    private final StmProperties props;
    private final WsTicketService tickets;

    public SignalingHandshakeInterceptor(DeviceTokenService deviceTokens, StmProperties props, WsTicketService tickets) {
        this.deviceTokens = deviceTokens;
        this.props = props;
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest s) {
            HttpServletRequest r = s.getServletRequest();

            String ticket = r.getParameter("ticket");
            if (ticket != null) {
                Optional<WsTicketService.Grant> g = tickets.redeem(ticket);
                if (g.isPresent()) {
                    attributes.put("role", g.get().role());
                    attributes.put("name", g.get().name());
                    if (g.get().terminalId() != null) attributes.put("terminalId", g.get().terminalId());
                    return true;
                }
                return reject(response);
            }

            String role = r.getParameter("role");
            if ("kiosk".equals(role)) {
                String h = r.getHeader("Authorization");
                if (h != null && h.startsWith("Bearer ")) {
                    Optional<DevicePrincipal> p = deviceTokens.authenticate(h.substring(7).trim());
                    if (p.isPresent()) {
                        attributes.put("role", "KIOSK");
                        attributes.put("name", p.get().deviceId());
                        attributes.put("terminalId", p.get().terminalId());
                        return true;
                    }
                }
            } else if ("teller".equals(role) && props.allowTellerKeyInQuery()
                    && Hashing.constantTimeEquals(r.getParameter("key"), props.portalApiKey())) {
                String tellerId = r.getParameter("tellerId");
                attributes.put("role", "TELLER");
                attributes.put("name", tellerId == null || tellerId.isBlank() ? "teller" : tellerId);
                return true;
            }
        }
        return reject(response);
    }

    private static boolean reject(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
