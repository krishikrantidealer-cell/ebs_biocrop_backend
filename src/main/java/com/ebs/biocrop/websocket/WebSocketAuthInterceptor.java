package com.ebs.biocrop.websocket;

import com.ebs.biocrop.security.jwt.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Validates JWT access token and user identity during WebSocket handshake.
 * Expected query format: /ws?userId={userId}&token={jwtToken}
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);

    private final JwtTokenProvider jwtTokenProvider;

    public WebSocketAuthInterceptor(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        URI uri = request.getURI();
        MultiValueMap<String, String> queryParams = UriComponentsBuilder.fromUri(uri)
                .build()
                .getQueryParams();

        String userId = queryParams.getFirst("userId");
        String token = queryParams.getFirst("token");

        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7).trim();
        }

        if (userId == null || userId.isBlank() || token == null || token.isBlank()) {
            log.warn("[WS Auth] Handshake rejected - missing userId or token. Path: {}", uri.getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        userId = userId.trim();
        token = token.trim();

        if (!jwtTokenProvider.validateToken(token) || !jwtTokenProvider.isAccessToken(token)) {
            log.warn("[WS Auth] Handshake rejected - invalid or non-access JWT token for userId: {}", userId);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String tokenUserId = jwtTokenProvider.getUserIdFromToken(token);
        if (tokenUserId == null || !tokenUserId.equals(userId)) {
            log.warn("[WS Auth] Handshake rejected - token userId '{}' does not match query userId '{}'",
                    tokenUserId, userId);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String role = jwtTokenProvider.getRoleFromToken(token);
        attributes.put("userId", userId);
        attributes.put("role", role != null ? role : "ROLE_USER");
        attributes.put("lastActive", Instant.now().toEpochMilli());

        log.info("[WS Auth] Handshake approved for userId={}, role={}", userId, role);
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        if (exception != null) {
            log.warn("[WS Auth] Handshake completed with exception: {}", exception.getMessage());
        }
    }
}
