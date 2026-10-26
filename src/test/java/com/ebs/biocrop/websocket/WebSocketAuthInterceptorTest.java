package com.ebs.biocrop.websocket;

import com.ebs.biocrop.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpResponse response;

    @Mock
    private WebSocketHandler wsHandler;

    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthInterceptor(jwtTokenProvider);
    }

    @Test
    void beforeHandshake_shouldApprove_whenTokenAndUserIdAreValid() {
        URI uri = URI.create("wss://localhost:8080/ws?userId=user123&token=validToken");
        when(request.getURI()).thenReturn(uri);
        when(jwtTokenProvider.validateToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("validToken")).thenReturn("user123");
        when(jwtTokenProvider.getRoleFromToken("validToken")).thenReturn("ROLE_SELLER");

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertTrue(result);
        assertEquals("user123", attributes.get("userId"));
        assertEquals("ROLE_SELLER", attributes.get("role"));
        assertNotNull(attributes.get("lastActive"));
    }

    @Test
    void beforeHandshake_shouldReject_whenMissingParams() {
        URI uri = URI.create("wss://localhost:8080/ws");
        when(request.getURI()).thenReturn(uri);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void beforeHandshake_shouldReject_whenTokenIsInvalid() {
        URI uri = URI.create("wss://localhost:8080/ws?userId=user123&token=badToken");
        when(request.getURI()).thenReturn(uri);
        when(jwtTokenProvider.validateToken("badToken")).thenReturn(false);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void beforeHandshake_shouldReject_whenUserIdDoesNotMatchToken() {
        URI uri = URI.create("wss://localhost:8080/ws?userId=user123&token=validToken");
        when(request.getURI()).thenReturn(uri);
        when(jwtTokenProvider.validateToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("validToken")).thenReturn("differentUser");

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void beforeHandshake_shouldReject_whenTokenIsRefreshToken() {
        URI uri = URI.create("wss://localhost:8080/ws?userId=user123&token=refreshToken");
        when(request.getURI()).thenReturn(uri);
        when(jwtTokenProvider.validateToken("refreshToken")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("refreshToken")).thenReturn(false);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}
