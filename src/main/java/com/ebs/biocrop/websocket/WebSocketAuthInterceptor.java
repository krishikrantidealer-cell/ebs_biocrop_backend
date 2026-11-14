package com.ebs.biocrop.websocket;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.jwt.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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
import java.util.Optional;

/**
 * Validates JWT access token, user identity, and account active status during WebSocket handshake.
 * Strict by default: requires token via HTTP header (Authorization: Bearer <jwt> or Sec-WebSocket-Protocol)
 * to completely eliminate token exposure in URL access logs.
 * Any request containing a query-string token is strictly rejected by default to guarantee credentials
 * are never recorded in URL logs.
 * URL query parameter token can optionally be allowed via app.websocket.allow-query-token=true.
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final boolean allowQueryToken;

    @Autowired
    public WebSocketAuthInterceptor(
            JwtTokenProvider jwtTokenProvider,
            UserRepository userRepository,
            @Value("${app.websocket.allow-query-token:false}") boolean allowQueryToken) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
        this.allowQueryToken = allowQueryToken;
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

        // 1. Strictly reject any request containing a query-string token when query tokens are disabled
        // even if a valid header token is supplied, guaranteeing credentials are never present in URL logs.
        String queryToken = queryParams.getFirst("token");
        if (queryToken != null && !queryToken.isBlank()) {
            if (!allowQueryToken) {
                log.warn("[WS Auth] Handshake rejected - URL query parameter token is prohibited for security. Path: {}. Use Authorization header or Sec-WebSocket-Protocol.", uri.getPath());
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
        }

        String userId = queryParams.getFirst("userId");
        String token = null;
        String selectedSubprotocol = null;

        HttpHeaders headers = request.getHeaders();
        if (headers != null) {
            // 2. Check Authorization header (e.g. "Bearer eyJ...")
            String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7).trim();
            }

            // 3. Check Sec-WebSocket-Protocol header (browser WebSocket workaround for headers)
            if (token == null || token.isBlank()) {
                String protocolHeader = headers.getFirst("Sec-WebSocket-Protocol");
                if (protocolHeader != null && !protocolHeader.isBlank()) {
                    for (String candidate : protocolHeader.split(",")) {
                        String trimmed = candidate.trim();
                        if (!trimmed.isEmpty()) {
                            token = trimmed;
                            selectedSubprotocol = trimmed;
                            break;
                        }
                    }
                }
            }
        }

        // 4. Permissive query parameter fallback (only reachable when allowQueryToken == true)
        if ((token == null || token.isBlank()) && queryToken != null && !queryToken.isBlank()) {
            token = queryToken.trim();
        }

        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7).trim();
        }

        // If userId was not passed as query param, extract it directly from the valid token
        if ((userId == null || userId.isBlank()) && token != null && !token.isBlank() && jwtTokenProvider.validateToken(token)) {
            userId = jwtTokenProvider.getUserIdFromToken(token);
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

        // Validate account active status in database (prevents blocked/deleted users from reconnecting with unexpired JWT)
        if (userRepository != null) {
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                log.warn("[WS Auth] Handshake rejected - user not found in database: {}", userId);
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            User user = userOpt.get();
            if (Boolean.TRUE.equals(user.getIsBlocked())) {
                log.warn("[WS Auth] Handshake rejected - user account is blocked: {}", userId);
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            if (Boolean.TRUE.equals(user.getIsDeleted())) {
                log.warn("[WS Auth] Handshake rejected - user account is deactivated: {}", userId);
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
        }

        String role = jwtTokenProvider.getRoleFromToken(token);
        attributes.put("userId", userId);
        attributes.put("role", role != null ? role : "ROLE_USER");
        attributes.put("lastActive", Instant.now().toEpochMilli());

        if (selectedSubprotocol != null) {
            HttpHeaders resHeaders = response.getHeaders();
            if (resHeaders != null) {
                resHeaders.set("Sec-WebSocket-Protocol", selectedSubprotocol);
            }
        }

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
