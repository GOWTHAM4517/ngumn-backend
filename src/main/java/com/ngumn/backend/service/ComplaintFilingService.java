package com.ngumn.backend.service;

import com.ngumn.backend.dto.AuthorityChannelRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.notify.MailGateway;
import com.ngumn.backend.notify.NotifyGateway;
import com.ngumn.backend.repository.AuthorityChannelRepository;
import com.ngumn.backend.repository.ComplaintFilingRepository;
import com.ngumn.backend.repository.IncidentRepository;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Files complaints - and requests for help - with the real authorities,
 * through their official channels (AuthorityChannel), not only with the
 * department teams inside Raksio:
 *
 * <ul>
 *   <li><b>By email, by itself.</b> Once the person agrees, a new complaint is
 *       emailed to every channel with an address for it (the municipal
 *       corporation's grievance email, say): what, where (map link), when, the
 *       deadline, and - only if they agreed - their name and phone. The email
 *       carries a reply link: the department taps "Taken up", "Fixed" or "Not
 *       ours" on a small page (no login), and the person sees it in Raksio at
 *       once. Past the deadline, one reminder goes to the same address.</li>
 *   <li><b>One tap for the rest.</b> Helplines (1100, 1912, 112...), WhatsApp and
 *       portals open from the app with the complaint already written; the
 *       person can add the official complaint number they're given, and it
 *       stays with the complaint.</li>
 * </ul>
 *
 * Every attempt is a ComplaintFiling with what really happened - an email
 * service that isn't set up is NOT_SET_UP, never "sent".
 */
@Service
public class ComplaintFilingService {

    private static final Logger log = LoggerFactory.getLogger(ComplaintFilingService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);
    private static final Set<String> SELF_METHODS = Set.of("CALL", "WHATSAPP", "SMS", "PORTAL");
    private static final List<String> LEVELS = List.of("CITY", "STATE", "NATIONAL", "EMERGENCY");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthorityChannelRepository channels;
    private final ComplaintFilingRepository filings;
    private final IncidentRepository incidents;
    private final IncidentService incidentService;
    private final MailGateway mail;
    private final NgumnWebSocketHandler webSocket;
    private final Executor sender;
    private final ExecutorService ownPool;

    @Value("${ngumn.public-url:}")
    private String publicUrl = "";

    @Autowired
    public ComplaintFilingService(AuthorityChannelRepository channels, ComplaintFilingRepository filings, IncidentRepository incidents,
                                  IncidentService incidentService, MailGateway mail, NgumnWebSocketHandler webSocket) {
        this(channels, filings, incidents, incidentService, mail, webSocket, null);
    }

    /** sender null: emails go out on a small background pool (tests pass Runnable::run). */
    public ComplaintFilingService(AuthorityChannelRepository channels, ComplaintFilingRepository filings, IncidentRepository incidents,
                                  IncidentService incidentService, MailGateway mail, NgumnWebSocketHandler webSocket, Executor sender) {
        this.channels = channels;
        this.filings = filings;
        this.incidents = incidents;
        this.incidentService = incidentService;
        this.mail = mail;
        this.webSocket = webSocket;
        this.ownPool = sender == null ? Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "complaint-mail");
            t.setDaemon(true);
            return t;
        }) : null;
        this.sender = sender != null ? sender : ownPool;
    }

    @PreDestroy
    void stop() {
        if (ownPool != null) ownPool.shutdown();
    }

    // ------------------------------------------------------------------ the official channels

    /**
     * The first time the server starts with none: the official channels that
     * hold for all of Andhra Pradesh / India. The admin adds the city's own
     * (the municipal corporation's grievance email...) in the Control Centre.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedDefaults() {
        if (channels.count() > 0) return;
        channels.saveAll(List.of(
                AuthorityChannel.builder().name("Electricity complaints - 1912").team(ResponderTeam.ELECTRICITY).level("STATE").phone("1912")
                        .note("Power cuts, a live wire, a streetlight - day and night (APCPDCL in Vijayawada).").sortOrder(10).build(),
                AuthorityChannel.builder().name("AP grievances - PGRS (Meekosam)").level("STATE").phone("1100").portalUrl("https://meekosam.ap.gov.in")
                        .note("Andhra Pradesh's official grievance system. Call 1100, or file on the portal (it sends an OTP to your Aadhaar-linked phone).")
                        .sortOrder(20).build(),
                AuthorityChannel.builder().name("AP government on WhatsApp (Mana Mitra)").level("STATE").whatsapp("+919552300009")
                        .note("The state's official WhatsApp services number - say Hi and pick the service.").sortOrder(30).build(),
                AuthorityChannel.builder().name("CPGRAMS - central government grievances").level("NATIONAL").portalUrl("https://pgportal.gov.in")
                        .note("For central government departments - railways, post, national highways, banks.").sortOrder(40).build(),
                AuthorityChannel.builder().name("National highways - NHAI 1033").level("NATIONAL").phone("1033")
                        .types("POTHOLE,ROAD_DIGGING,BREAKDOWN,ACCIDENT,TRAFFIC_JAM")
                        .note("Only on a national highway (NH-16, NH-65 and others).").sortOrder(45).build(),
                AuthorityChannel.builder().name("Police - 112").team(ResponderTeam.POLICE).level("EMERGENCY").phone("112")
                        .note("Danger or a crime happening now.").sortOrder(50).build(),
                AuthorityChannel.builder().name("Ambulance - 108").team(ResponderTeam.AMBULANCE).level("EMERGENCY").phone("108").sortOrder(51).build(),
                AuthorityChannel.builder().name("Fire - 101").team(ResponderTeam.FIRE).level("EMERGENCY").phone("101").sortOrder(52).build()
        ));
        log.info("Added the default official complaint channels");
    }

    /**
     * The channels for this kind of thing at this spot: the city's first, then
     * the state's and the country's; for help needed right now the emergency
     * numbers come first.
     */
    public List<AuthorityChannel> channelsFor(IncidentType type, Double lat, Double lng) {
        boolean urgent = !type.complaint();
        return channels.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .filter(c -> c.covers(type))
                .filter(c -> within(c, lat, lng))
                .sorted(Comparator.comparingInt((AuthorityChannel c) -> rank(c.getLevel(), urgent))
                        .thenComparing(c -> c.getSortOrder() != null ? c.getSortOrder() : 100))
                .toList();
    }

    private static int rank(String level, boolean urgent) {
        if (urgent && "EMERGENCY".equals(level)) return -1;
        int i = LEVELS.indexOf(level);
        return i < 0 ? LEVELS.size() : i;
    }

    private static boolean within(AuthorityChannel c, Double lat, Double lng) {
        if (c.getCenterLatitude() == null || c.getCenterLongitude() == null || c.getRadiusKm() == null || lat == null || lng == null) return true;
        return GeoUtil.distanceMeters(c.getCenterLatitude(), c.getCenterLongitude(), lat, lng) <= c.getRadiusKm() * 1000;
    }

    /** For the app: the channels for a kind of complaint where the person is. */
    public List<Map<String, Object>> channelsView(IncidentType type, Double lat, Double lng) {
        return channelsFor(type, lat, lng).stream().map(c -> channelView(c, type.complaint())).toList();
    }

    private Map<String, Object> channelView(AuthorityChannel c, boolean complaint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("level", c.getLevel());
        m.put("note", c.getNote());
        m.put("phone", blankToNull(c.getPhone()));
        m.put("whatsapp", blankToNull(c.getWhatsapp()));
        m.put("sms", blankToNull(c.getSms()));
        m.put("portalUrl", blankToNull(c.getPortalUrl()));
        m.put("email", c.hasEmail() ? c.getEmail().trim() : null);
        // Emailed by Raksio itself when the complaint is sent (and the server can send email).
        m.put("emailedAutomatically", complaint && c.hasEmail() && Boolean.TRUE.equals(c.getAutoEmail()));
        return m;
    }

    // ------------------------------------------------------------------ filing a new complaint

    /**
     * Right after a complaint is made (and the person agreed to file it
     * officially): emails every channel with an address for it. Asking again
     * for the same open complaint doesn't email twice. base: this server's
     * address for the reply link (PUBLIC_URL wins).
     */
    public List<ComplaintFiling> fileNew(User reporter, List<Long> incidentIds, String base) {
        List<ComplaintFiling> made = new ArrayList<>();
        for (Long id : incidentIds) {
            Incident i = incidents.findById(id).orElse(null);
            if (i == null || !i.getType().complaint() || !i.getReporter().getId().equals(reporter.getId())) continue;
            List<ComplaintFiling> before = filings.findByIncidentIdOrderByCreatedAtAsc(i.getId());
            for (AuthorityChannel c : channelsFor(i.getType(), i.getLatitude(), i.getLongitude())) {
                if (!c.hasEmail() || !Boolean.TRUE.equals(c.getAutoEmail())) continue;
                if (before.stream().anyMatch(f -> "EMAIL".equals(f.getMethod()) && c.getId().equals(f.getChannelId()))) continue;
                ComplaintFiling f = filings.save(ComplaintFiling.builder()
                        .incidentId(i.getId()).channelId(c.getId()).channelName(c.getName())
                        .method("EMAIL").status(mail.configured() ? "SENDING" : "NOT_SET_UP")
                        .detail(mail.configured() ? "to " + c.getEmail().trim() : "Raksio can't send email yet - the server has no email service set up")
                        .token(newToken())
                        .build());
                made.add(f);
                if (mail.configured()) {
                    String link = base(base);
                    Long fid = f.getId();
                    sender.execute(() -> sendFirst(fid, link));
                }
            }
            if (!made.isEmpty()) changed(i);
        }
        return made;
    }

    private void sendFirst(Long filingId, String base) {
        try {
            ComplaintFiling f = filings.findById(filingId).orElse(null);
            if (f == null) return;
            Incident i = incidents.findById(f.getIncidentId()).orElse(null);
            AuthorityChannel c = f.getChannelId() != null ? channels.findById(f.getChannelId()).orElse(null) : null;
            if (i == null || c == null || !c.hasEmail()) return;
            NotifyGateway.Result r = mail.send(email(i, c, f, base, false));
            f.setStatus(r.status() == NotifyGateway.Status.SENT ? "SENT" : r.status() == NotifyGateway.Status.FAILED ? "FAILED" : "NOT_SET_UP");
            f.setDetail(cut((r.status() == NotifyGateway.Status.SENT ? "to " + c.getEmail().trim() : r.detail()), 300));
            filings.save(f);
            changed(i);
        } catch (RuntimeException e) {
            log.warn("Emailing complaint filing {} failed: {}", filingId, e.toString());
        }
    }

    /** The email to the department (reminder = past its deadline, still not fixed). */
    MailGateway.Mail email(Incident i, AuthorityChannel c, ComplaintFiling f, String base, boolean reminder) {
        boolean share = Boolean.TRUE.equals(i.getShareContact());
        User who = i.getReporter();
        String link = base + "/complaint-reply.html?t=" + f.getToken();
        String map = mapLink(i);
        String what = IncidentService.summary(i);
        String subject = (reminder ? "Reminder - past its deadline: " : "Complaint: ") + what + " [Raksio #" + i.getId() + "]";
        String due = i.getDueAt() != null ? i.getDueAt().format(TIME) : null;
        String contact = share
                ? who.getName() + (who.getPhone() != null && !who.getPhone().isBlank() ? ", " + who.getPhone() : "") + " (shared with their permission - you can reply to this email)"
                : "The citizen chose not to share their contact details - please answer through the link below.";

        StringBuilder text = new StringBuilder();
        if (reminder) text.append("This complaint is past the deadline it was filed with and is still open on Raksio.\n\n");
        text.append("A citizen filed this complaint for the ").append(c.getName()).append(" through Raksio, the NGUMN road-safety app.\n\n")
                .append("What: ").append(what).append('\n');
        if (i.getDescription() != null) text.append("Details: \"").append(i.getDescription()).append("\"\n");
        text.append("Where: ").append(map).append('\n')
                .append("Filed: ").append(i.getCreatedAt() != null ? i.getCreatedAt().format(TIME) : "now").append('\n');
        if (due != null) text.append("To be fixed by: ").append(due).append(" (the time Raksio tells the citizen)\n");
        text.append("From: ").append(contact).append("\n\n")
                .append("Please tell the citizen what happens - one click, no login:\n").append(link).append("\n\n")
                .append("Raksio complaint number: ").append(i.getId()).append('\n');

        String html = "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.5;color:#0F1B2D;max-width:600px\">"
                + (reminder ? "<p style=\"background:#FDECEC;color:#9B1C1C;padding:10px 12px;border-radius:8px\"><b>Reminder:</b> this complaint is past its deadline and still open.</p>" : "")
                + "<p>A citizen filed this complaint for the <b>" + esc(c.getName()) + "</b> through <b>Raksio</b>, the NGUMN road-safety app.</p>"
                + "<table style=\"border-collapse:collapse;width:100%\">"
                + row("What", esc(what))
                + (i.getDescription() != null ? row("Details", "&ldquo;" + esc(i.getDescription()) + "&rdquo;") : "")
                + row("Where", "<a href=\"" + esc(map) + "\">Open the spot on the map</a> (" + String.format(Locale.ROOT, "%.5f, %.5f", i.getLatitude(), i.getLongitude()) + ")")
                + row("Filed", esc(i.getCreatedAt() != null ? i.getCreatedAt().format(TIME) : "now"))
                + (due != null ? row("To be fixed by", esc(due)) : "")
                + row("From", esc(contact))
                + row("Raksio no.", String.valueOf(i.getId()))
                + "</table>"
                + "<p style=\"margin-top:18px\">Please tell the citizen what happens - one click, no login:</p>"
                + "<p><a href=\"" + esc(link) + "\" style=\"display:inline-block;background:#1E5AA8;color:#ffffff;text-decoration:none;padding:12px 18px;border-radius:8px;font-weight:bold\">Answer this complaint</a></p>"
                + "<p style=\"color:#5B6B80;font-size:12px\">Taken up, fixed, or not your department - the citizen sees your answer in the app straight away. "
                + "Sent by Raksio on the citizen's behalf.</p></div>";
        return new MailGateway.Mail(c.getEmail().trim(), c.getName(), null, share && who.getEmail() != null ? who.getEmail() : null,
                share ? who.getName() : null, subject, html, text.toString());
    }

    private static String row(String k, String v) {
        return "<tr><td style=\"padding:6px 10px 6px 0;color:#5B6B80;vertical-align:top;white-space:nowrap\">" + k + "</td><td style=\"padding:6px 0\">" + v + "</td></tr>";
    }

    // ------------------------------------------------------------------ what the person sees

    /**
     * For the complaint's screen: every channel it can go to (with the text to
     * send), and every filing so far with its status. The person who made it,
     * its team or an admin.
     */
    public Map<String, Object> view(User viewer, Long incidentId) {
        Incident i = incidents.findById(incidentId).orElseThrow(() -> ApiException.notFound("Complaint not found"));
        boolean mine = i.getReporter().getId().equals(viewer.getId());
        if (!mine && viewer.getResponderTeam() != i.getTeam() && viewer.getRole() != Role.ADMIN) throw ApiException.notFound("Complaint not found");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incidentId", i.getId());
        m.put("complaint", i.getType().complaint());
        m.put("message", message(i));
        m.put("shareContact", Boolean.TRUE.equals(i.getShareContact()));
        m.put("emailReady", mail.configured());
        m.put("channels", channelsView(i.getType(), i.getLatitude(), i.getLongitude()));
        m.put("filings", filings.findByIncidentIdOrderByCreatedAtAsc(i.getId()).stream().map(ComplaintFilingService::filingView).toList());
        return m;
    }

    static Map<String, Object> filingView(ComplaintFiling f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("channelId", f.getChannelId());
        m.put("channelName", f.getChannelName());
        m.put("method", f.getMethod());
        m.put("status", f.getStatus());
        m.put("detail", f.getDetail());
        m.put("officialRef", f.getOfficialRef());
        m.put("responderName", f.getResponderName());
        m.put("responseNote", f.getResponseNote());
        m.put("createdAt", f.getCreatedAt());
        m.put("respondedAt", f.getRespondedAt());
        m.put("remindedAt", f.getRemindedAt());
        return m;
    }

    /** The complaint written out - for WhatsApp, SMS, a portal or reading out on a call. */
    public static String message(Incident i) {
        StringBuilder s = new StringBuilder(i.getType().complaint() ? "Complaint: " : "Help needed: ").append(IncidentService.summary(i)).append('.');
        if (i.getDescription() != null) s.append(" Details: ").append(i.getDescription().trim()).append('.');
        s.append(" Location: ").append(mapLink(i));
        if (i.getCreatedAt() != null) s.append(" (reported ").append(i.getCreatedAt().format(TIME)).append(')');
        s.append(i.getType().complaint() ? ". Raksio complaint no. " : ". Raksio request no. ").append(i.getId()).append('.');
        return s.toString();
    }

    /** The person called / messaged / opened a portal from the app. Opening the same one again within 10 minutes is the same filing. */
    public Map<String, Object> record(User reporter, Long incidentId, Long channelId, String method) {
        Incident i = incidents.findById(incidentId).orElseThrow(() -> ApiException.notFound("Complaint not found"));
        if (!i.getReporter().getId().equals(reporter.getId())) throw ApiException.notFound("Complaint not found");
        String how = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!SELF_METHODS.contains(how)) throw ApiException.badRequest("Call, WhatsApp, SMS or portal.");
        AuthorityChannel c = channels.findById(channelId).orElseThrow(() -> ApiException.notFound("That channel isn't there any more."));
        LocalDateTime recent = LocalDateTime.now().minusMinutes(10);
        ComplaintFiling f = filings.findByIncidentIdOrderByCreatedAtAsc(i.getId()).stream()
                .filter(x -> how.equals(x.getMethod()) && c.getId().equals(x.getChannelId()) && x.getCreatedAt().isAfter(recent))
                .findFirst()
                .orElseGet(() -> filings.save(ComplaintFiling.builder()
                        .incidentId(i.getId()).channelId(c.getId()).channelName(c.getName())
                        .method(how).status("OPENED")
                        .detail(switch (how) {
                            case "CALL" -> "called " + c.getPhone();
                            case "WHATSAPP" -> "WhatsApp to " + c.getWhatsapp();
                            case "SMS" -> "SMS to " + c.getSms();
                            default -> "opened " + c.getPortalUrl();
                        })
                        .build()));
        changed(i);
        return filingView(f);
    }

    /** The official complaint number the person was given (on the call, on the portal...). Blank clears it. */
    public Map<String, Object> setReference(User reporter, Long filingId, String ref) {
        ComplaintFiling f = filings.findById(filingId).orElseThrow(() -> ApiException.notFound("Not found"));
        Incident i = incidents.findById(f.getIncidentId()).orElseThrow(() -> ApiException.notFound("Not found"));
        if (!i.getReporter().getId().equals(reporter.getId())) throw ApiException.notFound("Not found");
        String v = ref == null ? null : ref.trim();
        if (v != null && v.length() > 60) throw ApiException.badRequest("That number is too long.");
        f.setOfficialRef(v == null || v.isEmpty() ? null : v);
        filings.save(f);
        changed(i);
        return filingView(f);
    }

    // ------------------------------------------------------------------ the department's answer (reply link, no login)

    /** What the department sees when it opens the link in its email. */
    public Map<String, Object> replyView(String token) {
        ComplaintFiling f = byToken(token);
        Incident i = incidents.findById(f.getIncidentId()).orElseThrow(() -> ApiException.notFound("This link isn't valid."));
        boolean share = Boolean.TRUE.equals(i.getShareContact());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("department", f.getChannelName());
        m.put("raksioNo", i.getId());
        m.put("what", IncidentService.summary(i));
        m.put("details", i.getDescription());
        m.put("latitude", i.getLatitude());
        m.put("longitude", i.getLongitude());
        m.put("map", mapLink(i));
        m.put("filedAt", i.getCreatedAt() != null ? i.getCreatedAt().format(TIME) : null);
        m.put("dueAt", i.getDueAt() != null ? i.getDueAt().format(TIME) : null);
        m.put("status", i.getStatus().name());
        m.put("withdrawn", i.getStatus() == IncidentStatus.CANCELLED);
        m.put("citizen", share ? i.getReporter().getName() : null);
        m.put("phone", share ? i.getReporter().getPhone() : null);
        m.put("answer", f.answered() ? f.getStatus() : null);
        m.put("answeredBy", f.getResponderName());
        m.put("answerNote", f.getResponseNote());
        m.put("officialRef", f.getOfficialRef());
        return m;
    }

    /**
     * The department answers: ACCEPT (taken up), RESOLVE (fixed) or REJECT
     * (not theirs). Once fixed, that's the answer. The person is told at once.
     */
    public Map<String, Object> reply(String token, String action, String name, String note, String ref) {
        ComplaintFiling f = byToken(token);
        Incident i = incidents.findById(f.getIncidentId()).orElseThrow(() -> ApiException.notFound("This link isn't valid."));
        if (i.getStatus() == IncidentStatus.CANCELLED) throw ApiException.badRequest("The citizen has withdrawn this complaint - nothing more to do.");
        if ("RESOLVED".equals(f.getStatus())) throw ApiException.badRequest("You've already marked it fixed.");
        String status = switch (action == null ? "" : action.trim().toUpperCase(Locale.ROOT)) {
            case "ACCEPT" -> "ACCEPTED";
            case "RESOLVE" -> "RESOLVED";
            case "REJECT" -> "REJECTED";
            default -> throw ApiException.badRequest("Taken up, fixed or not yours?");
        };
        String who = cut(name, 80);
        String why = cut(note, 300);
        f.setStatus(status);
        f.setResponderName(who);
        f.setResponseNote(why);
        f.setRespondedAt(LocalDateTime.now());
        String r = cut(ref, 60);
        if (r != null) f.setOfficialRef(r);
        filings.save(f);
        String handler = f.getChannelName() + (who != null ? " (" + who + ")" : "");
        incidentService.externalUpdate(i.getId(), handler, status, why);
        changed(i);
        return replyView(token);
    }

    private ComplaintFiling byToken(String token) {
        if (token == null || token.length() < 20) throw ApiException.notFound("This link isn't valid.");
        return filings.findByToken(token).orElseThrow(() -> ApiException.notFound("This link isn't valid."));
    }

    // ------------------------------------------------------------------ reminders

    /** Every 10 minutes: one reminder email for a complaint past its deadline that the department hasn't marked fixed. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void scheduledReminders() {
        try {
            int n = remindOverdue(base(null));
            if (n > 0) log.info("Sent {} complaint reminder(s)", n);
        } catch (RuntimeException e) {
            log.warn("Complaint reminders failed: {}", e.toString());
        }
    }

    public int remindOverdue(String base) {
        if (!mail.configured()) return 0;
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (ComplaintFiling f : filings.findByMethodAndCreatedAtAfter("EMAIL", now.minusDays(60))) {
            if (f.getRemindedAt() != null || !("SENT".equals(f.getStatus()) || "ACCEPTED".equals(f.getStatus()))) continue;
            Incident i = incidents.findById(f.getIncidentId()).orElse(null);
            if (i == null || !i.getStatus().active() || i.getDueAt() == null || !now.isAfter(i.getDueAt())) continue;
            AuthorityChannel c = f.getChannelId() != null ? channels.findById(f.getChannelId()).orElse(null) : null;
            if (c == null || !c.hasEmail()) continue;
            NotifyGateway.Result r = mail.send(email(i, c, f, base, true));
            f.setRemindedAt(now);
            if (r.status() != NotifyGateway.Status.SENT) f.setDetail(cut("Reminder: " + r.detail(), 300));
            filings.save(f);
            changed(i);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ the admin keeps the list

    public List<Map<String, Object>> adminList() {
        return channels.findAllByOrderBySortOrderAscIdAsc().stream().map(ComplaintFilingService::adminView).toList();
    }

    static Map<String, Object> adminView(AuthorityChannel c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("team", c.getTeam() != null ? c.getTeam().name() : null);
        m.put("types", c.getTypes());
        m.put("level", c.getLevel());
        m.put("email", c.getEmail());
        m.put("phone", c.getPhone());
        m.put("whatsapp", c.getWhatsapp());
        m.put("sms", c.getSms());
        m.put("portalUrl", c.getPortalUrl());
        m.put("note", c.getNote());
        m.put("autoEmail", c.getAutoEmail());
        m.put("active", c.getActive());
        m.put("sortOrder", c.getSortOrder());
        m.put("centerLatitude", c.getCenterLatitude());
        m.put("centerLongitude", c.getCenterLongitude());
        m.put("radiusKm", c.getRadiusKm());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    public Map<String, Object> adminSave(Long id, AuthorityChannelRequest r) {
        AuthorityChannel c = id == null ? new AuthorityChannel() : channels.findById(id).orElseThrow(() -> ApiException.notFound("Channel not found"));
        String name = r.getName() != null ? r.getName().trim() : (id == null ? "" : c.getName());
        if (name.isEmpty()) throw ApiException.badRequest("Give it a name.");
        c.setName(cut(name, 80));
        c.setTeam(blank(r.getTeam()) ? null : team(r.getTeam()));
        c.setTypes(types(r.getTypes()));
        String level = blank(r.getLevel()) ? "CITY" : r.getLevel().trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(level)) throw ApiException.badRequest("Level: CITY, STATE, NATIONAL or EMERGENCY.");
        c.setLevel(level);
        String email = blankToNull(r.getEmail());
        if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw ApiException.badRequest("That email address doesn't look right.");
        c.setEmail(email);
        c.setPhone(phone(r.getPhone()));
        c.setWhatsapp(phone(r.getWhatsapp()));
        c.setSms(phone(r.getSms()));
        String url = blankToNull(r.getPortalUrl());
        if (url != null && !url.startsWith("https://") && !url.startsWith("http://")) throw ApiException.badRequest("The portal address starts with https://");
        c.setPortalUrl(cut(url, 200));
        c.setNote(cut(blankToNull(r.getNote()), 200));
        c.setAutoEmail(r.getAutoEmail() == null || r.getAutoEmail());
        c.setActive(r.getActive() == null || r.getActive());
        c.setSortOrder(r.getSortOrder() != null ? r.getSortOrder() : 100);
        boolean fenced = r.getCenterLatitude() != null && r.getCenterLongitude() != null && r.getRadiusKm() != null;
        c.setCenterLatitude(fenced ? r.getCenterLatitude() : null);
        c.setCenterLongitude(fenced ? r.getCenterLongitude() : null);
        c.setRadiusKm(fenced ? Math.max(1, r.getRadiusKm()) : null);
        if (c.getEmail() == null && c.getPhone() == null && c.getWhatsapp() == null && c.getSms() == null && c.getPortalUrl() == null) {
            throw ApiException.badRequest("Add at least one way to reach them - email, phone, WhatsApp, SMS or a portal.");
        }
        return adminView(channels.save(c));
    }

    public void adminDelete(Long id) {
        AuthorityChannel c = channels.findById(id).orElseThrow(() -> ApiException.notFound("Channel not found"));
        channels.delete(c);
    }

    /** For the admin dashboard: can the server send complaint emails? */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>(mail.describe());
        m.put("channels", channels.count());
        m.put("withEmail", channels.findByActiveTrueOrderBySortOrderAscIdAsc().stream().filter(AuthorityChannel::hasEmail).count());
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static ResponderTeam team(String s) {
        try {
            return ResponderTeam.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Department: POLICE, AMBULANCE, FIRE, MUNICIPAL or ELECTRICITY.");
        }
    }

    private static String types(String raw) {
        if (blank(raw)) return null;
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            try {
                IncidentType.valueOf(t);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Not a kind of complaint: " + t);
            }
            if (!out.contains(t)) out.add(t);
        }
        return out.isEmpty() ? null : cut(String.join(",", out), 400);
    }

    private static String phone(String s) {
        String v = blankToNull(s);
        if (v == null) return null;
        if (!v.matches("[+0-9 ()-]{3,20}")) throw ApiException.badRequest("That number doesn't look right: " + v);
        return v;
    }

    static String mapLink(Incident i) {
        return "https://maps.google.com/?q=" + String.format(Locale.ROOT, "%.6f,%.6f", i.getLatitude(), i.getLongitude());
    }

    private String base(String requestBase) {
        String b = publicUrl != null && !publicUrl.isBlank() ? publicUrl.trim() : requestBase != null ? requestBase : "https://ngumn-backend.onrender.com";
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private void changed(Incident i) {
        webSocket.broadcast("INCIDENT", Map.of("id", i.getId(), "team", i.getTeam().name(), "status", i.getStatus().name()));
    }

    private static String newToken() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
