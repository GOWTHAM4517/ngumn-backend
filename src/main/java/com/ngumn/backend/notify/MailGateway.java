package com.ngumn.backend.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends real email through an email service's HTTPS API - no extra library:
 *
 * <ul>
 *   <li><b>Brevo</b> (MAIL_PROVIDER=brevo, the default): free for 300 emails a
 *       day; the sender address (MAIL_FROM) only has to be verified in Brevo -
 *       a Gmail address works, no domain needed.</li>
 *   <li><b>Resend</b> (MAIL_PROVIDER=resend): needs a domain verified in Resend.</li>
 * </ul>
 *
 * Off until MAIL_API_KEY and MAIL_FROM are set - then every attempt comes back
 * NOT_SET_UP and is recorded that way, never shown as sent.
 */
@Component
public class MailGateway {

    private static final Logger log = LoggerFactory.getLogger(MailGateway.class);
    private static final Pattern ID = Pattern.compile("\"(?:messageId|id)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"");

    /** One email. replyTo / cc may be null. */
    public record Mail(String to, String toName, List<String> cc, String replyTo, String replyToName,
                       String subject, String html, String text) {
    }

    @Value("${ngumn.mail.provider:brevo}")
    private String provider = "brevo";

    @Value("${ngumn.mail.api-key:}")
    private String apiKey = "";

    @Value("${ngumn.mail.from:}")
    private String from = "";

    @Value("${ngumn.mail.from-name:Raksio complaints}")
    private String fromName = "Raksio complaints";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public boolean configured() {
        return !blank(apiKey) && !blank(from);
    }

    /** For the admin dashboard. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", configured() ? provider() : "none");
        m.put("from", configured() ? from : null);
        return m;
    }

    private String provider() {
        return provider == null ? "brevo" : provider.trim().toLowerCase(Locale.ROOT);
    }

    public NotifyGateway.Result send(Mail mail) {
        if (!configured()) return NotifyGateway.Result.notSetUp("No email service on the server (MAIL_API_KEY / MAIL_FROM not set)");
        boolean resend = provider().equals("resend");
        String body = resend ? resendJson(mail) : brevoJson(mail);
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(resend ? "https://api.resend.com/emails" : "https://api.brevo.com/v3/smtp/email"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (resend) req.header("Authorization", "Bearer " + apiKey.trim());
        else req.header("api-key", apiKey.trim());
        try {
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                Matcher m = ID.matcher(res.body());
                return new NotifyGateway.Result(NotifyGateway.Status.SENT, m.find() ? m.group(1) : "accepted");
            }
            Matcher m = MESSAGE.matcher(res.body());
            String why = "HTTP " + res.statusCode() + (m.find() ? ": " + m.group(1) : "");
            log.warn("Email to {} failed: {}", mail.to(), why);
            return new NotifyGateway.Result(NotifyGateway.Status.FAILED, cut(why));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Email to {} failed: {}", mail.to(), e.toString());
            return new NotifyGateway.Result(NotifyGateway.Status.FAILED, cut(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    String brevoJson(Mail m) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"sender\":{\"name\":").append(q(fromName)).append(",\"email\":").append(q(from.trim())).append("},");
        sb.append("\"to\":[{\"email\":").append(q(m.to())).append(m.toName() != null ? ",\"name\":" + q(m.toName()) : "").append("}],");
        if (m.cc() != null && !m.cc().isEmpty()) {
            List<String> cc = new ArrayList<>();
            for (String c : m.cc()) cc.add("{\"email\":" + q(c) + "}");
            sb.append("\"cc\":[").append(String.join(",", cc)).append("],");
        }
        if (m.replyTo() != null) {
            sb.append("\"replyTo\":{\"email\":").append(q(m.replyTo())).append(m.replyToName() != null ? ",\"name\":" + q(m.replyToName()) : "").append("},");
        }
        sb.append("\"subject\":").append(q(m.subject())).append(',');
        sb.append("\"htmlContent\":").append(q(m.html())).append(',');
        sb.append("\"textContent\":").append(q(m.text()));
        return sb.append('}').toString();
    }

    String resendJson(Mail m) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"from\":").append(q(fromName + " <" + from.trim() + ">")).append(',');
        sb.append("\"to\":[").append(q(m.to())).append("],");
        if (m.cc() != null && !m.cc().isEmpty()) {
            List<String> cc = new ArrayList<>();
            for (String c : m.cc()) cc.add(q(c));
            sb.append("\"cc\":[").append(String.join(",", cc)).append("],");
        }
        if (m.replyTo() != null) sb.append("\"reply_to\":").append(q(m.replyTo())).append(',');
        sb.append("\"subject\":").append(q(m.subject())).append(',');
        sb.append("\"html\":").append(q(m.html())).append(',');
        sb.append("\"text\":").append(q(m.text()));
        return sb.append('}').toString();
    }

    private static String q(String s) {
        return AuthorityForwarder.quote(s == null ? "" : s);
    }

    private static String cut(String s) {
        return s == null ? null : s.length() > 290 ? s.substring(0, 290) : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
