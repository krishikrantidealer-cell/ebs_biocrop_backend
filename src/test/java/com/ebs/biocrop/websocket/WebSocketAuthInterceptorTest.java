package com.ebs.biocrop.websocket;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpResponse response;

    @Mock
    private WebSocketHandler wsHandler;

    private WebSocketAuthInterceptor permissiveInterceptor;
    private WebSocketAuthInterceptor strictInterceptor;

    @BeforeEach
    void setUp() {
        permissiveInterceptor = new WebSocketAuthInterceptor(jwtTokenProvider, userRepository, true);
        strictInterceptor = new WebSocketAuthInterceptor(jwtTokenProvider, userRepository, false);
    }

    @Test
    void beforeHandshake_shouldReject_whenQueryTokenInStrictMode() {
        URI uri = URI.create("wss://localhost:8080/ws?userId=user123&token=validToken");
        when(request.getURI()).thenReturn(uri);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = strictInterceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void beforeHandshake_shouldReject_whenBothQueryTokenAndValidHeaderPresent_inStrictMode() {
        URI uri = URI.create("wss://localhost:8080/ws?userId=user-from-token&token=leakedToken");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer validToken");

        when(request.getURI()).thenReturn(uri);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = strictInterceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void beforeHandshake_shouldApprove_whenTokenInAuthorizationHeader_inStrictMode() {
        URI uri = URI.create("wss://localhost:8080/ws");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer validToken");

        when(request.getURI()).thenReturn(uri);
        when(request.getHeaders()).thenReturn(headers);
        when(jwtTokenProvider.validateToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("validToken")).thenReturn("user-from-token");
        when(jwtTokenProvider.getRoleFromToken("validToken")).thenReturn("ROLE_CUSTOMER");

        User activeUser = new User();
        activeUser.setId("user-from-token");
        activeUser.setIsBlocked(false);
        activeUser.setIsDeleted(false);
        when(userRepository.findById("user-from-token")).thenReturn(Optional.of(activeUser));

        Map<String, Object> attributes = new HashMap<>();
        boolean result = strictInterceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertTrue(result);
        assertEquals("user-from-token", attributes.get("userId"));
        assertEquals("ROLE_CUSTOMER", attributes.get("role"));
    }

    @Test
    void beforeHandshake_shouldApprove_whenTokenInSecWebSocketProtocol_inStrictMode() {
        URI uri = URI.create("wss://localhost:8080/ws");
        HttpHeaders reqHeaders = new HttpHeaders();
        reqHeaders.set("Sec-WebSocket-Protocol", "validToken");
        HttpHeaders resHeaders = new HttpHeaders();

        when(request.getURI()).thenReturn(uri);
        when(request.getHeaders()).thenReturn(reqHeaders);
        when(response.getHeaders()).thenReturn(resHeaders);
        when(jwtTokenProvider.validateToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("validToken")).thenReturn("user-proto");
        when(jwtTokenProvider.getRoleFromToken("validToken")).thenReturn("ROLE_SELLER");

        User activeUser = new User();
        activeUser.setId("user-proto");
        activeUser.setIsBlocked(false);
        activeUser.setIsDeleted(false);
        when(userRepository.findById("user-proto")).thenReturn(Optional.of(activeUser));

        Map<String, Object> attributes = new HashMap<>();
        boolean result = strictInterceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertTrue(result);
        assertEquals("user-proto", attributes.get("userId"));
        assertEquals("ROLE_SELLER", attributes.get("role"));
        assertEquals("validToken", resHeaders.getFirst("Sec-WebSocket-Protocol"));
    }

    @Test
    void beforeHandshake_shouldApprove_whenTokenAndUserIdAreValid_inPermissiveMode() {
        URI uri = URI.create("wss://localhost:8080/ws?userId=user123&token=validToken");
        when(request.getURI()).thenReturn(uri);
        when(jwtTokenProvider.validateToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("validToken")).thenReturn("user123");
        when(jwtTokenProvider.getRoleFromToken("validToken")).thenReturn("ROLE_SELLER");

        User activeUser = new User();
        activeUser.setId("user123");
        activeUser.setIsBlocked(false);
        activeUser.setIsDeleted(false);
        when(userRepository.findById("user123")).thenReturn(Optional.of(activeUser));

        Map<String, Object> attributes = new HashMap<>();
        boolean result = permissiveInterceptor.beforeHandshake(request, response, wsHandler, attributes);

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
        boolean result = permissiveInterceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void beforeHandshake_shouldReject_whenTokenIsInvalid() {
        URI uri = URI.create("wss://localhost:8080/ws?userId=user123&token=badToken");
        when(request.getURI()).thenReturn(uri);
        when(jwtTokenProvider.validateToken("badToken")).thenReturn(false);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = permissiveInterceptor.beforeHandshake(request, response, wsHandler, attributes);

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
        boolean result = permissiveInterceptor.beforeHandshake(request, response, wsHandler, attributes);

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
        boolean result = permissiveInterceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void beforeHandshake_shouldReject_whenUserIsBlockedInDatabase() {
        URI uri = URI.create("wss://localhost:8080/ws?userId=user123&token=validToken");
        when(request.getURI()).thenReturn(uri);
        when(jwtTokenProvider.validateToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("validToken")).thenReturn("user123");

        User blockedUser = new User();
        blockedUser.setId("user123");
        blockedUser.setIsBlocked(true);
        when(userRepository.findById("user123")).thenReturn(Optional.of(blockedUser));

        Map<String, Object> attributes = new HashMap<>();
        boolean result = permissiveInterceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void beforeHandshake_shouldReject_whenUserIsDeletedInDatabase() {
        URI uri = URI.create("wss://localhost:8080/ws?userId=user123&token=validToken");
        when(request.getURI()).thenReturn(uri);
        when(jwtTokenProvider.validateToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("validToken")).thenReturn("user123");

        User deletedUser = new User();
        deletedUser.setId("user123");
        deletedUser.setIsDeleted(true);
        when(userRepository.findById("user123")).thenReturn(Optional.of(deletedUser));

        Map<String, Object> attributes = new HashMap<>();
        boolean result = permissiveInterceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void beforeHandshake_shouldReject_whenUserNotFoundInDatabase() {
        URI uri = URI.create("wss://localhost:8080/ws?userId=user123&token=validToken");
        when(request.getURI()).thenReturn(uri);
        when(jwtTokenProvider.validateToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.isAccessToken("validToken")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("validToken")).thenReturn("user123");
        when(userRepository.findById("user123")).thenReturn(Optional.empty());

        Map<String, Object> attributes = new HashMap<>();
        boolean result = permissiveInterceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}
