package com.ngumn.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tiny helper to wrap a broadcast payload as {"type": "...", "data": ...}
 * using the Jackson ObjectMapper that Spring already brings in via
 * spring-boot-starter-webmvc, so no extra dependency is needed.
 */
final class SimpleJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SimpleJson() {
    }

    static String envelope(String type, Object payload) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", type);
            envelope.put("data", payload);
            return MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\",\"data\":null}";
        }
    }
}
