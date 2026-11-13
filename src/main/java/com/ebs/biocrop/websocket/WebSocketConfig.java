package com.ebs.biocrop.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Registers the /ws single-multiplexed WebSocket endpoint with JWT handshake
 * authentication and strict origin policies aligned with production domains.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler webSocketHandler;
    private final WebSocketAuthInterceptor authInterceptor;
    private final List<String> allowedOrigins;

    public WebSocketConfig(
            RealtimeWebSocketHandler webSocketHandler,
            WebSocketAuthInterceptor authInterceptor,
            @Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.webSocketHandler = webSocketHandler;
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> originPatterns = new ArrayList<>();
        if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
            originPatterns.addAll(allowedOrigins);
        }
        originPatterns.add("http://localhost:*");
        originPatterns.add("http://127.0.0.1:*");
        originPatterns.add("https://ebskrishidukaan.com");
        originPatterns.add("https://*.ebskrishidukaan.com");

        registry.addHandler(webSocketHandler, "/ws")
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns(originPatterns.toArray(new String[0]));
    }
}
