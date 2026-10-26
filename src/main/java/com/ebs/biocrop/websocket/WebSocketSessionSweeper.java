package com.ebs.biocrop.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically sweeps WebSocket sessions to detect and evict half-open or dead
 * connections where no heartbeat PING has been received within the idle timeout.
 */
@Component
public class WebSocketSessionSweeper {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionSweeper.class);

    private final RealtimeWebSocketHandler webSocketHandler;
    private final long idleTimeoutMillis;

    public WebSocketSessionSweeper(
            RealtimeWebSocketHandler webSocketHandler,
            @Value("${app.websocket.idle-timeout-ms:60000}") long idleTimeoutMillis) {
        this.webSocketHandler = webSocketHandler;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    @Scheduled(fixedDelayString = "${app.websocket.sweep-interval-ms:30000}")
    public void sweepStaleSessions() {
        try {
            int activeBefore = webSocketHandler.getActiveSessionCount();
            if (activeBefore > 0) {
                webSocketHandler.cleanupStaleSessions(idleTimeoutMillis);
            }
        } catch (Exception e) {
            log.error("[WS Sweeper] Error during session sweep: {}", e.getMessage(), e);
        }
    }
}
