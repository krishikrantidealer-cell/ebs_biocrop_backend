package com.ebs.biocrop.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default WebSocketService delegating to the local RealtimeWebSocketHandler.
 * Serves as an extension point for multi-instance Redis Pub/Sub cluster broadcasting
 * when scaling across multiple servers.
 */
@Service
public class WebSocketServiceImpl implements WebSocketService {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServiceImpl.class);

    private final RealtimeWebSocketHandler handler;

    public WebSocketServiceImpl(RealtimeWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void broadcast(String eventName, Object data) {
        log.debug("[WS Service] Broadcasting event: {}", eventName);
        handler.broadcastEvent(eventName, data);
    }

    @Override
    public void sendToUser(String userId, String eventName, Object data) {
        log.debug("[WS Service] Sending event {} to user {}", eventName, userId);
        handler.sendToUser(userId, eventName, data);
    }

    @Override
    public int getActiveSessionCount() {
        return handler.getActiveSessionCount();
    }
}
