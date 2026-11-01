package com.ngumn.backend.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sends real SMS and places real automatic voice calls through Twilio's REST
 * API (plain HTTPS - no extra library). Used to reach emergency contacts after
 * a crash or SOS, and authority control rooms (AuthorityForwarder).
 *
 * Off until the server has TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and
 * TWILIO_FROM_NUMBER. Without them every attempt comes back NOT_SET_UP - it is
 * recorded honestly, never shown as "sent". (In India, SMS from a business
 * sender also needs DLT registration; voice calls don't. See README.)
 */
@Component
public class NotifyGateway {

    private static final Logger log = LoggerFactory.getLogger(NotifyGateway.class);
    private static final Pattern SID = Pattern.compile("\"sid\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"");

    public enum Status { SENT, FAILED, NOT_SET_UP }

    public record Result(Status status, String detail) {
        public static Result notSetUp(String why) { return new Result(Status.NOT_SET_UP, why); }
    }

    @Value("${ngumn.notify.twilio.account-sid:}")
    private String accountSid;

    @Value("${ngumn.notify.twilio.auth-token:}")
    private String authToken;

    @Value("${ngumn.notify.twilio.from:}")
    private String fromNumber;

    @Value("${ngumn.notify.sms-enabled:true}")
    private boolean smsEnabled;

    @Value("${ngumn.notify.calls-enabled:true}")
    private boolean callsEnabled;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public boolean configured() {
        return !blank(accountSid) && !blank(authToken) && !blank(fromNumber);
    }

    public boolean smsReady() {
        return configured() && smsEnabled;
    }

    public boolean callsReady() {
        return configured() && callsEnabled;
    }

    /** For the admin dashboard: which channels really work on this server. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", configured() ? "Twilio" : "none");
        m.put("sms", smsReady());
        m.put("calls", callsReady());
        m.put("from", configured() ? fromNumber : null);
        return m;
    }

    public Result sms(String to, String text) {
        if (!configured()) return Result.notSetUp("No SMS provider on the server (TWILIO_* not set)");
        if (!smsEnabled) return Result.notSetUp("SMS turned off (SOS_SMS_ENABLED=false)");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", to);
        form.put("From", fromNumber);
        form.put("Body", text.length() > 600 ? text.substring(0, 600) : text);
        return post("Messages.json", form);
    }

    /**
     * Rings the number and reads the message out (twice), so it gets through
     * even to someone who doesn't read texts.
     */
    public Result call(String to, String spoken) {
        if (!configured()) return Result.notSetUp("No calling provider on the server (TWILIO_* not set)");
        if (!callsEnabled) return Result.notSetUp("Automatic calls turned off (SOS_CALLS_ENABLED=false)");
        String say = xml(spoken);
        String twiml = "<Response><Say language=\"en-IN\">" + say + "</Say><Pause length=\"1\"/>"
                + "<Say language=\"en-IN\">I repeat. " + say + "</Say></Response>";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", to);
        form.put("From", fromNumber);
        form.put("Twiml", twiml);
        return post("Calls.json", form);
    }

    private Result post(String resource, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        String auth = Base64.getEncoder().encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/" + resource))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                Matcher m = SID.matcher(res.body());
                return new Result(Status.SENT, m.find() ? m.group(1) : "accepted");
            }
            Matcher m = MESSAGE.matcher(res.body());
            String why = "HTTP " + res.statusCode() + (m.find() ? ": " + m.group(1) : "");
            log.warn("Twilio {} to {} failed: {}", resource, form.get("To"), why);
            return new Result(Status.FAILED, cut(why));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Twilio {} to {} failed: {}", resource, form.get("To"), e.toString());
            return new Result(Status.FAILED, cut(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String cut(String s) {
        return s == null ? null : s.length() > 290 ? s.substring(0, 290) : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
