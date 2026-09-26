package com.ngumn.backend.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Broadcasts live updates (vehicle movement, alerts, emergency events) to
 * every connected client (mobile app + admin dashboard). Intentionally a
 * plain raw WebSocket (no STOMP) to keep the mechanism easy to explain:
 * every connected client simply receives every JSON message broadcast
 * on the server.
 */
@Component
public class NgumnWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast(String type, Object payload) {
        String json = SimpleJson.envelope(type, payload);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException ignored) {
                // Best-effort broadcast; a dead session will be cleaned up
                // on its own close callback.
            }
        }
    }

    public int connectedClients() {
        return sessions.size();
    }
}
