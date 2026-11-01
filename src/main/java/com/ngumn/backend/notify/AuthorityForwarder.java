package com.ngumn.backend.notify;

import com.ngumn.backend.entity.Incident;
import com.ngumn.backend.entity.ResponderTeam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Passes a new help request or complaint straight on to the real authority,
 * outside Raksio, as soon as it's made:
 *
 * <ul>
 *   <li><b>Webhook</b> (AUTHORITY_WEBHOOK_POLICE, _AMBULANCE, _FIRE, _MUNICIPAL,
 *       _ELECTRICITY): a JSON POST to the department's own system, a control-room
 *       dashboard, or a script that emails/WhatsApps the officer in charge.</li>
 *   <li><b>SMS</b> (AUTHORITY_SMS_POLICE, ...): a text to the control-room numbers
 *       (comma separated), through NotifyGateway.</li>
 * </ul>
 *
 * Raksio's own responder accounts get every request in the app regardless;
 * this is for departments that don't use the app yet. Nothing set = OFF, and
 * the request says so ("forward: OFF") - it is never claimed as sent.
 */
@Component
public class AuthorityForwarder {

    private static final Logger log = LoggerFactory.getLogger(AuthorityForwarder.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM, hh:mm a", Locale.ENGLISH);

    private final Environment env;
    private final NotifyGateway gateway;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AuthorityForwarder(Environment env, NotifyGateway gateway) {
        this.env = env;
        this.gateway = gateway;
    }

    public String webhookFor(ResponderTeam team) {
        return env.getProperty("ngumn.authority.webhook." + team.name().toLowerCase(Locale.ROOT), "").trim();
    }

    public List<String> smsNumbersFor(ResponderTeam team) {
        String raw = env.getProperty("ngumn.authority.sms." + team.name().toLowerCase(Locale.ROOT), "");
        List<String> out = new ArrayList<>();
        for (String n : raw.split(",")) if (!n.isBlank()) out.add(n.trim());
        return out;
    }

    /** For the admin dashboard: where each team's requests go besides the app. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (ResponderTeam t : ResponderTeam.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("webhook", !webhookFor(t).isEmpty());
            row.put("smsNumbers", smsNumbersFor(t).size());
            m.put(t.name(), row);
        }
        return m;
    }

    /**
     * Sends it on; returns what happened in a few words for Incident.forwardStatus
     * ("webhook SENT, SMS 2/2", "OFF").
     */
    public String forward(Incident i, String summary, String mapLink, String headline) {
        ResponderTeam team = i.getTeam();
        List<String> parts = new ArrayList<>();
        String hook = webhookFor(team);
        if (!hook.isEmpty()) {
            parts.add("webhook " + (postJson(hook, json(i, summary, mapLink, headline)) ? "SENT" : "FAILED"));
        }
        List<String> numbers = smsNumbersFor(team);
        if (!numbers.isEmpty()) {
            int ok = 0;
            String text = "Raksio - " + headline + " #" + i.getId() + ": " + summary
                    + (i.getDescription() != null ? " \"" + i.getDescription() + "\"" : "")
                    + " Location: " + mapLink;
            for (String n : numbers) {
                if (gateway.sms(n, text).status() == NotifyGateway.Status.SENT) ok++;
            }
            parts.add("SMS " + ok + "/" + numbers.size());
        }
        return parts.isEmpty() ? "OFF" : String.join(", ", parts);
    }

    private boolean postJson(String url, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Raksio-NGUMN/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() >= 200 && res.statusCode() < 300) return true;
            log.warn("Authority webhook {} answered HTTP {}", url, res.statusCode());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Authority webhook {} failed: {}", url, e.toString());
        }
        return false;
    }

    private static String json(Incident i, String summary, String mapLink, String headline) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", headline);
        m.put("id", i.getId());
        m.put("caseKey", i.getCaseKey());
        m.put("type", i.getType().name());
        m.put("typeLabel", i.getType().label());
        m.put("complaint", i.getType().complaint());
        m.put("team", i.getTeam().name());
        m.put("summary", summary);
        m.put("description", i.getDescription());
        m.put("priority", i.getPriority());
        m.put("status", i.getStatus().name());
        m.put("latitude", i.getLatitude());
        m.put("longitude", i.getLongitude());
        m.put("map", mapLink);
        m.put("reporterName", i.getReporter().getName());
        m.put("source", i.getSource());
        m.put("createdAt", i.getCreatedAt() != null ? i.getCreatedAt().format(TIME) : null);
        m.put("dueAt", i.getDueAt() != null ? i.getDueAt().format(TIME) : null);
        m.put("escalationLevel", i.getEscalationLevel());
        return toJson(m);
    }

    /** Small JSON writer for flat maps (strings, numbers, booleans, null). */
    static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append(quote(v.toString()));
        }
        return sb.append('}').toString();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
