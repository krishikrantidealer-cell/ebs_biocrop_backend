package com.ebs.biocrop.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the /ws single-multiplexed WebSocket endpoint with JWT handshake
 * authentication and production CORS patterns.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler webSocketHandler;
    private final WebSocketAuthInterceptor authInterceptor;

    public WebSocketConfig(
            RealtimeWebSocketHandler webSocketHandler,
            WebSocketAuthInterceptor authInterceptor) {
        this.webSocketHandler = webSocketHandler;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler, "/ws")
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
