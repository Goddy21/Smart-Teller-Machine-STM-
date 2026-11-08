package com.stm.middleware.signaling;

import com.stm.middleware.config.StmProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final SignalingHandler handler;
    private final SignalingHandshakeInterceptor interceptor;
    private final StmProperties props;

    public WebSocketConfig(SignalingHandler handler, SignalingHandshakeInterceptor interceptor, StmProperties props) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.props = props;
    }

    /**
     * Same-origin pages and non-browser clients (no Origin header) are always accepted. Other browser origins must be
     * listed in stm.cors-allowed-origins, the same list the REST API uses.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/signaling")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns(props.allowedOrigins().toArray(String[]::new));
    }
}
