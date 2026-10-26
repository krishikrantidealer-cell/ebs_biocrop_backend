package com.ebs.biocrop.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, resilient WebSocket handler for EBS Biocrop.
 * Features:
 * - Single multiplexed endpoint /ws
 * - Multi-device/multi-session tracking per user
 * - PING/PONG keep-alive handling for reverse proxy and network stability
 * - Thread-safe send via ConcurrentWebSocketSessionDecorator (slow consumer protection)
 * - Safe error isolation: broken sockets pruned without interrupting other connections
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024; // 512 KB

    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public RealtimeWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        String userId = (String) rawSession.getAttributes().getOrDefault("userId", rawSession.getId());

        // Decorate session for thread-safety and slow consumer buffer management
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                rawSession,
                SEND_TIME_LIMIT_MS,
                SEND_BUFFER_SIZE_LIMIT
        );

        activeSessions.put(session.getId(), session);
        userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session.getId());
        session.getAttributes().put("lastActive", Instant.now().toEpochMilli());

        log.info("[WS] Client connected: sessionId={}, userId={}. Total active sessions: {}",
                session.getId(), userId, activeSessions.size());

        // Send connection ACK according to architecture specification
        broadcastToSession(session, Map.of(
                "event", "system:connected",
                "data", Map.of(
                        "userId", userId,
                        "message", "Connected to EBS Biocrop Real-time Pipeline"
                ),
                "timestamp", Instant.now().toString()
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        session.getAttributes().put("lastActive", Instant.now().toEpochMilli());
        String payload = message.getPayload();

        // Handle Heartbeat PING from Flutter or KD Panel
        if (payload.contains("\"type\":\"PING\"") || payload.contains("\"PING\"")) {
            String pongPayload = String.format("{\"type\":\"PONG\",\"timestamp\":\"%s\"}", Instant.now());
            session.sendMessage(new TextMessage(pongPayload));
            return;
        }

        // Additional client text messages can be logged safely without throwing
        log.debug("[WS] Received message from sessionId={}: {}", session.getId(), payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        cleanupSession(session.getId(), status.getCode(), status.getReason());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("[WS] Transport error on session {}: {}", session.getId(), exception.getMessage());
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (Exception ignored) {
        }
        cleanupSession(session.getId(), CloseStatus.SERVER_ERROR.getCode(), exception.getMessage());
    }

    /**
     * Broadcasts a domain event to all connected sessions conforming to standard envelope:
     * { "event": "<domain>:<action>", "data": { ... }, "timestamp": "<ISO-8601>" }
     */
    public void broadcastEvent(String eventName, Object data) {
        Map<String, Object> envelope = Map.of(
                "event", eventName,
                "data", data,
                "timestamp", Instant.now().toString()
        );

        String jsonMessage;
        try {
            jsonMessage = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("[WS] Serialization error for event {}: {}", eventName, e.getMessage());
            return;
        }

        TextMessage textMessage = new TextMessage(jsonMessage);
        for (WebSocketSession session : activeSessions.values()) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(textMessage);
                } catch (IOException e) {
                    log.warn("[WS] Error sending event {} to session {}: {}. Pruning dead session.",
                            eventName, session.getId(), e.getMessage());
                    cleanupSession(session.getId(), CloseStatus.PROTOCOL_ERROR.getCode(), "Write failure");
                    try {
                        session.close(CloseStatus.PROTOCOL_ERROR);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    /**
     * Sends an event envelope only to the sessions associated with a specific user.
     */
    public void sendToUser(String userId, String eventName, Object data) {
        if (userId == null) return;
        Set<String> sessionIds = userSessions.get(userId);
        if (sessionIds == null || sessionIds.isEmpty()) return;

        Map<String, Object> envelope = Map.of(
                "event", eventName,
                "data", data,
                "timestamp", Instant.now().toString()
        );

        String jsonMessage;
        try {
            jsonMessage = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("[WS] Serialization error for user {} event {}: {}", userId, eventName, e.getMessage());
            return;
        }

        TextMessage textMessage = new TextMessage(jsonMessage);
        for (String sessionId : sessionIds) {
            WebSocketSession session = activeSessions.get(sessionId);
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(textMessage);
                } catch (IOException e) {
                    log.warn("[WS] Error sending to user {} session {}: {}", userId, sessionId, e.getMessage());
                    cleanupSession(sessionId, CloseStatus.PROTOCOL_ERROR.getCode(), "Write failure");
                }
            }
        }
    }

    /**
     * Cleanly closes and removes any stale sessions that have not pinged or sent data
     * within the specified idle threshold.
     */
    public void cleanupStaleSessions(long maxIdleMillis) {
        long now = Instant.now().toEpochMilli();
        for (WebSocketSession session : activeSessions.values()) {
            Object lastActiveObj = session.getAttributes().get("lastActive");
            if (lastActiveObj instanceof Long lastActive) {
                if (now - lastActive > maxIdleMillis) {
                    log.info("[WS Sweeper] Evicting stale idle session {}. Idle ms: {}",
                            session.getId(), (now - lastActive));
                    try {
                        session.close(CloseStatus.SESSION_NOT_RELIABLE);
                    } catch (Exception ignored) {
                    }
                    cleanupSession(session.getId(), CloseStatus.SESSION_NOT_RELIABLE.getCode(), "Idle timeout");
                }
            }
        }
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    public Set<String> getConnectedUsers() {
        return Collections.unmodifiableSet(userSessions.keySet());
    }

    private void broadcastToSession(WebSocketSession session, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.error("[WS] Failed to send message to session {}: {}", session.getId(), e.getMessage());
        }
    }

    private void cleanupSession(String sessionId, int code, String reason) {
        WebSocketSession removed = activeSessions.remove(sessionId);
        if (removed != null) {
            String userId = (String) removed.getAttributes().get("userId");
            if (userId != null) {
                Set<String> sessions = userSessions.get(userId);
                if (sessions != null) {
                    sessions.remove(sessionId);
                    if (sessions.isEmpty()) {
                        userSessions.remove(userId);
                    }
                }
            }
            log.info("[WS] Client session removed: sessionId={}, code={}, reason={}. Remaining active: {}",
                    sessionId, code, reason, activeSessions.size());
        }
    }
}
