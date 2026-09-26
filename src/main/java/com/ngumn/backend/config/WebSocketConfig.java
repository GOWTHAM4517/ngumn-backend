package com.ngumn.backend.config;

import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final NgumnWebSocketHandler handler;

    public WebSocketConfig(NgumnWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Mobile app / dashboard connect to ws://<host>:<port>/ws/live
        registry.addHandler(handler, "/ws/live").setAllowedOrigins("*");
    }
}
