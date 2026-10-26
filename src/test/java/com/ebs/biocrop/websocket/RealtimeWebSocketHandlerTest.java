package com.ebs.biocrop.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealtimeWebSocketHandlerTest {

    private ObjectMapper objectMapper;
    private RealtimeWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new RealtimeWebSocketHandler(objectMapper);
    }

    @Test
    void afterConnectionEstablished_shouldSendConnectedFrame() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", "user-456");
        when(session.getId()).thenReturn("sess-1");
        when(session.getAttributes()).thenReturn(attributes);

        handler.afterConnectionEstablished(session);

        assertEquals(1, handler.getActiveSessionCount());

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        TextMessage sent = captor.getValue();
        JsonNode json = objectMapper.readTree(sent.getPayload());
        assertEquals("system:connected", json.get("event").asText());
        assertEquals("user-456", json.get("data").get("userId").asText());
        assertNotNull(json.get("timestamp"));
    }

    @Test
    void handleTextMessage_shouldReplyPong_whenPingReceived() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", "user-456");
        when(session.getId()).thenReturn("sess-1");
        when(session.getAttributes()).thenReturn(attributes);

        handler.afterConnectionEstablished(session);

        TextMessage pingMessage = new TextMessage("{\"type\": \"PING\", \"timestamp\": \"2026-10-24T18:00:00Z\"}");
        handler.handleTextMessage(session, pingMessage);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(2)).sendMessage(captor.capture());

        TextMessage lastSent = captor.getAllValues().get(captor.getAllValues().size() - 1);
        JsonNode json = objectMapper.readTree(lastSent.getPayload());
        assertEquals("PONG", json.get("type").asText());
        assertNotNull(json.get("timestamp"));
    }

    @Test
    void broadcastEvent_shouldSendToAllOpenSessions() throws Exception {
        WebSocketSession session1 = mock(WebSocketSession.class);
        WebSocketSession session2 = mock(WebSocketSession.class);
        Map<String, Object> attr1 = new HashMap<>();
        attr1.put("userId", "user-1");
        Map<String, Object> attr2 = new HashMap<>();
        attr2.put("userId", "user-2");

        when(session1.getId()).thenReturn("s1");
        when(session1.getAttributes()).thenReturn(attr1);
        when(session1.isOpen()).thenReturn(true);

        when(session2.getId()).thenReturn("s2");
        when(session2.getAttributes()).thenReturn(attr2);
        when(session2.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session1);
        handler.afterConnectionEstablished(session2);

        assertEquals(2, handler.getActiveSessionCount());

        handler.broadcastEvent("products:update", Map.of("productId", "p-100", "stock", 50));

        ArgumentCaptor<TextMessage> captor1 = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1, atLeast(2)).sendMessage(captor1.capture());

        TextMessage last1 = captor1.getAllValues().get(captor1.getAllValues().size() - 1);
        JsonNode json1 = objectMapper.readTree(last1.getPayload());
        assertEquals("products:update", json1.get("event").asText());
        assertEquals("p-100", json1.get("data").get("productId").asText());
    }

    @Test
    void broadcastEvent_shouldIsolateAndPruneDeadSession() throws Exception {
        WebSocketSession deadSession = mock(WebSocketSession.class);
        Map<String, Object> attr = new HashMap<>();
        attr.put("userId", "user-dead");

        when(deadSession.getId()).thenReturn("s-dead");
        when(deadSession.getAttributes()).thenReturn(attr);
        when(deadSession.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(deadSession);
        assertEquals(1, handler.getActiveSessionCount());

        // Make subsequent sendMessage throw IOException simulating a broken pipe
        doThrow(new IOException("Broken pipe")).when(deadSession).sendMessage(any(TextMessage.class));

        assertDoesNotThrow(() -> handler.broadcastEvent("orders:new", Map.of("orderId", "o-1")));

        // Dead session should be pruned from activeSessions
        assertEquals(0, handler.getActiveSessionCount());
    }

    @Test
    void cleanupStaleSessions_shouldCloseSessionsOlderThanThreshold() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attr = new HashMap<>();
        attr.put("userId", "user-1");
        when(session.getId()).thenReturn("s-idle");
        when(session.getAttributes()).thenReturn(attr);

        handler.afterConnectionEstablished(session);
        assertEquals(1, handler.getActiveSessionCount());

        // Set lastActive to 2 minutes ago
        attr.put("lastActive", System.currentTimeMillis() - 120_000);

        handler.cleanupStaleSessions(60_000);

        assertEquals(0, handler.getActiveSessionCount());
        verify(session).close(CloseStatus.SESSION_NOT_RELIABLE);
    }
}
