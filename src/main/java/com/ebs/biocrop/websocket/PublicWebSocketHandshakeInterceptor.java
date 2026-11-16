package com.ebs.biocrop.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Allows anonymous storefront visitors to connect to /ws/public to receive
 * real-time public catalog invalidation events (collections, categories, products).
 */
@Component
public class PublicWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        attributes.put("publicOnly", Boolean.TRUE);
        attributes.put("userId", "guest-" + UUID.randomUUID());
        attributes.put("role", "ROLE_GUEST");
        attributes.put("lastActive", Instant.now().toEpochMilli());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
    }
}
