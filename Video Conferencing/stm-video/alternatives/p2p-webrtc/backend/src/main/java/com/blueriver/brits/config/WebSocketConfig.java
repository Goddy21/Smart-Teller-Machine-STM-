package com.blueriver.brits.config;

import com.blueriver.brits.webrtc.websocket.WebRTCSignalingHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final WebRTCSignalingHandler handler;
  @Value("${brits.webrtc.allowed-origins}") String[] origins;
  @Value("${brits.webrtc.dev-identity:false}") boolean devIdentity;

  public WebSocketConfig(WebRTCSignalingHandler handler) { this.handler = handler; }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, "/stm/ws/signaling")
        .addInterceptors(new IdentityHandshakeInterceptor(devIdentity))
        .setAllowedOriginPatterns(origins);
  }

  @Bean
  public ServletServerContainerFactoryBean webSocketContainer() {
    ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
    c.setMaxTextMessageBufferSize(64 * 1024);   // SDP offers are a few KB
    c.setMaxSessionIdleTimeout(90_000L);        // clients ping every 25 s
    return c;
  }
}
