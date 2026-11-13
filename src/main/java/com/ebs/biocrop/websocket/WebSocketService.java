package com.ebs.biocrop.websocket;

/**
 * Service abstraction for publishing real-time events to connected clients.
 */
public interface WebSocketService {

    /**
     * Broadcasts an event to all connected sessions conforming to standard envelope:
     * { "event": "<domain>:<action>", "data": { ... }, "timestamp": "<ISO-8601>" }
     */
    void broadcast(String eventName, Object data);

    /**
     * Sends an event envelope only to the sessions owned by the given userId.
     */
    void sendToUser(String userId, String eventName, Object data);

    /**
     * Sends an event envelope to all active sessions whose authenticated user possesses the given role.
     */
    void sendToRole(String role, String eventName, Object data);

    /**
     * Forcibly closes and disconnects all active WebSocket sessions owned by the given userId.
     */
    void disconnectUser(String userId);

    /**
     * Returns total currently active WebSocket sessions.
     */
    int getActiveSessionCount();
}
