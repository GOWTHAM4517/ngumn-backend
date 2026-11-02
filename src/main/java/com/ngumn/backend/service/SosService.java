package com.ngumn.backend.service;

import com.ngumn.backend.dto.IncidentRequest;
import com.ngumn.backend.dto.IncidentResponse;
import com.ngumn.backend.dto.SosRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.event.HospitalChosenEvent;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.notify.NotifyGateway;
import com.ngumn.backend.repository.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Crash alerts and SOS.
 *
 * <pre>
 *   phone: crash detected, nobody tapped "I'm OK" in 30 s   (or SOS pressed)
 *     -> POST /api/sos
 *   server:
 *     1. help requests: police + ambulance (crash), or the team for the SOS reason
 *     2. every emergency contact, in order, at the same time:
 *          - inside Raksio, if the contact uses Raksio with that number
 *          - SMS with a live-location link            (if an SMS provider is set up)
 *          - an automatic voice call reading it out   (if a calling provider is set up)
 *     3. every attempt is recorded (SosDelivery) - the crash screen and the
 *        admin dashboard show exactly who was reached and how
 *   phone: rings contact no. 1 itself (Android), and shows the results
 * </pre>
 *
 * "I'm OK" afterwards cancels the help requests and tells the contacts it was
 * a false alarm.
 */
@Service
public class SosService {

    private static final Logger log = LoggerFactory.getLogger(SosService.class);
    /** The live-location link stops showing a position after this. */
    static final Duration TRACK_WINDOW = Duration.ofHours(24);
    /** "Share live location" lasts an hour on the phone; the link stops a little after that even if the phone never says so. */
    static final Duration SHARE_WINDOW = Duration.ofMinutes(65);
    /** Pressing again within this while one is active returns the same one. */
    static final Duration REPEAT_WINDOW = Duration.ofMinutes(30);
    static final Set<String> REASONS = Set.of("ACCIDENT", "MEDICAL", "FIRE", "UNSAFE");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SosEventRepository sosRepository;
    private final SosDeliveryRepository deliveryRepository;
    private final EmergencyContactRepository contactRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentService incidentService;
    private final AlertService alertService;
    private final NotifyGateway gateway;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "sos-notify");
        t.setDaemon(true);
        return t;
    });

    @Value("${ngumn.public-url:}")
    private String publicUrl;

    public SosService(SosEventRepository sosRepository, SosDeliveryRepository deliveryRepository,
                      EmergencyContactRepository contactRepository, UserRepository userRepository,
                      VehicleRepository vehicleRepository, IncidentRepository incidentRepository,
                      IncidentService incidentService, AlertService alertService, NotifyGateway gateway) {
        this.sosRepository = sosRepository;
        this.deliveryRepository = deliveryRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.incidentRepository = incidentRepository;
        this.incidentService = incidentService;
        this.alertService = alertService;
        this.gateway = gateway;
    }

    @PreDestroy
    void stop() {
        pool.shutdown();
    }

    /** The address contacts open - PUBLIC_URL if set, else the address the app reached us on. */
    String base(String requestBase) {
        String b = publicUrl != null && !publicUrl.isBlank() ? publicUrl.trim() : requestBase;
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    // ------------------------------------------------------------------ raising

    public Map<String, Object> trigger(User user, SosRequest r, String requestBase) {
        String kind = r.getKind() == null ? "" : r.getKind().trim().toUpperCase(Locale.ROOT);
        if (!kind.equals("CRASH") && !kind.equals("SOS") && !kind.equals("CHECKIN") && !kind.equals("SHARE")) {
            throw ApiException.badRequest("kind must be CRASH, SOS, CHECKIN or SHARE");
        }
        String reason = kind.equals("CRASH") ? "ACCIDENT"
                : kind.equals("CHECKIN") || kind.equals("SHARE") ? "UNSAFE"
                : r.getReason() == null || r.getReason().isBlank() ? "ACCIDENT" : r.getReason().trim().toUpperCase(Locale.ROOT);
        if (!REASONS.contains(reason)) throw ApiException.badRequest("Unknown SOS reason: " + r.getReason());
        if (r.getLatitude() < -90 || r.getLatitude() > 90 || r.getLongitude() < -180 || r.getLongitude() > 180) {
            throw ApiException.badRequest("That location doesn't look right.");
        }
        String base = base(requestBase);

        // Same person, still active, a few minutes ago: the same alert (a retry after no signal, a double tap).
        // (Only the same kind - a crash during an active Safe Walk alert still calls for help.)
        Optional<SosEvent> running = sosRepository.findByUserIdAndStatus(user.getId(), "ACTIVE").stream()
                .filter(e -> e.getKind().equals(kind))
                .filter(e -> e.getCreatedAt().isAfter(LocalDateTime.now().minus(REPEAT_WINDOW)))
                .max(Comparator.comparing(SosEvent::getCreatedAt));
        if (running.isPresent()) return view(running.get(), base, true);

        SosEvent event = sosRepository.save(SosEvent.builder()
                .user(user)
                .kind(kind)
                .reason(reason)
                .latitude(r.getLatitude())
                .longitude(r.getLongitude())
                .speedKmh(r.getSpeedKmh())
                .description(trimOrNull(r.getDescription()))
                .status("ACTIVE")
                .trackToken(token())
                .build());

        // 1. Help requests to the right teams. (A missed Safe Walk check-in only
        //    tells the contacts - they know the person and can call them first.
        //    Sharing live location isn't an emergency at all.)
        List<IncidentResponse> requests = List.of();
        if (!kind.equals("CHECKIN") && !kind.equals("SHARE")) {
            IncidentRequest help = new IncidentRequest();
            help.setType(IncidentType.valueOf(reason));
            help.setLatitude(r.getLatitude());
            help.setLongitude(r.getLongitude());
            help.setVehicleKind(r.getVehicleKind());
            if (reason.equals("ACCIDENT")) {
                // Nobody answered, or they pressed SOS for an accident - assume someone is hurt so an ambulance comes too.
                help.setInjured(1);
            }
            help.setDescription(describe(event, user));
            try {
                requests = incidentService.create(user, help, kind, event.getId());
                if (!requests.isEmpty()) event.setCaseKey(requests.get(0).getCaseKey());
                event = sosRepository.save(event);
            } catch (RuntimeException e) {
                // Contacts must still be told even if the help request failed.
                log.error("SOS {}: couldn't create help requests: {}", event.getId(), e.toString());
            }
        }

        // 2. Contacts - in the background so the phone gets its answer at once.
        final SosEvent saved = event;
        pool.submit(() -> {
            try {
                notifyContacts(saved, base, false);
            } catch (Exception e) {
                log.error("SOS {}: notifying contacts failed: {}", saved.getId(), e.toString());
            }
        });

        Map<String, Object> out = view(event, base, false);
        out.put("requests", requests);
        return out;
    }

    /** Keeps the live-location link up to date (also taken from live location sharing). */
    public Map<String, Object> updateLocation(User user, Long id, double lat, double lng, String requestBase) {
        SosEvent e = own(user, id);
        if (e.active()) {
            e.setLastLatitude(lat);
            e.setLastLongitude(lng);
            e.setLastSeenAt(LocalDateTime.now());
            sosRepository.save(e);
        }
        return view(e, base(requestBase), false);
    }

    /** "I'm OK": cancel the help requests and tell the contacts it was a false alarm. */
    public Map<String, Object> cancel(User user, Long id, String requestBase) {
        SosEvent e = own(user, id);
        String base = base(requestBase);
        if (!e.active()) return view(e, base, false);
        e.setStatus("CANCELLED");
        e.setClosedAt(LocalDateTime.now());
        sosRepository.save(e);
        if (e.getCaseKey() != null) {
            incidentRepository.findByCaseKey(e.getCaseKey()).stream()
                    .filter(i -> i.getStatus().active())
                    .findFirst()
                    .ifPresent(i -> incidentService.cancel(user, i.getId()));
        }
        // Stopping a location share isn't a false alarm - nobody needs telling.
        if ("SHARE".equals(e.getKind())) return view(e, base, false);
        pool.submit(() -> {
            try {
                notifyContacts(e, base, true);
            } catch (Exception ex) {
                log.error("SOS {}: false-alarm notice failed: {}", e.getId(), ex.toString());
            }
        });
        return view(e, base, false);
    }

    public Map<String, Object> get(User user, Long id, String requestBase) {
        SosEvent e = sosRepository.findById(id).orElseThrow(() -> ApiException.notFound("Alert not found"));
        if (!e.getUser().getId().equals(user.getId()) && user.getRole() != Role.ADMIN) throw ApiException.notFound("Alert not found");
        return view(e, base(requestBase), false);
    }

    public List<Map<String, Object>> active(User user, String requestBase) {
        return sosRepository.findByUserIdAndStatus(user.getId(), "ACTIVE").stream()
                .map(e -> view(e, base(requestBase), false)).toList();
    }

    /** Admin dashboard: every crash / SOS in the last `days`, with who was reached. */
    public List<Map<String, Object>> recent(User admin, int days, String requestBase) {
        if (admin.getRole() != Role.ADMIN) throw ApiException.forbidden("Only admins can see this.");
        return sosRepository.findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 90))))
                .stream().map(e -> {
                    Map<String, Object> m = view(e, base(requestBase), false);
                    m.put("userName", e.getUser().getName());
                    m.put("userEmail", e.getUser().getEmail());
                    m.put("userPhone", e.getUser().getPhone());
                    return m;
                }).toList();
    }

    /** Admin: close it (help has been given). */
    public Map<String, Object> close(User admin, Long id, String requestBase) {
        if (admin.getRole() != Role.ADMIN) throw ApiException.forbidden("Only admins can do this.");
        SosEvent e = sosRepository.findById(id).orElseThrow(() -> ApiException.notFound("Alert not found"));
        if (e.active()) {
            e.setStatus("CLOSED");
            e.setClosedAt(LocalDateTime.now());
            sosRepository.save(e);
        }
        return view(e, base(requestBase), false);
    }

    /** How long the link shows a position: an hour for a location share, a day for an emergency. */
    static Duration window(SosEvent e) {
        return "SHARE".equals(e.getKind()) ? SHARE_WINDOW : TRACK_WINDOW;
    }

    /** Closes alerts left active for a day, and location shares after an hour (EscalationService). */
    public int closeStale() {
        int n = 0;
        for (SosEvent e : sosRepository.findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime.now().minusDays(7))) {
            if (e.active() && e.getCreatedAt().isBefore(LocalDateTime.now().minus(window(e)))) {
                e.setStatus("CLOSED");
                e.setClosedAt(LocalDateTime.now());
                sosRepository.save(e);
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ live-location link (public)

    /**
     * What /track.html shows a contact: first name, what happened, where they
     * are now and who's coming. Nothing else about the person; no position
     * once it's over or a day old.
     */
    public Map<String, Object> track(String token) {
        SosEvent e = sosRepository.findByTrackToken(token).orElseThrow(() -> ApiException.notFound("This link has expired or is wrong."));
        boolean live = e.active() && e.getCreatedAt().isAfter(LocalDateTime.now().minus(window(e)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", firstName(e.getUser().getName()));
        m.put("kind", e.getKind());
        m.put("reason", e.getReason());
        m.put("headline", headline(e));
        m.put("status", live ? "ACTIVE" : e.getStatus().equals("CANCELLED") ? "CANCELLED" : "CLOSED");
        m.put("createdAt", e.getCreatedAt().toString());
        if (live) {
            double[] where = latest(e);
            m.put("latitude", where[0]);
            m.put("longitude", where[1]);
            m.put("updatedAt", lastSeen(e).toString());
            m.put("phone", e.getUser().getPhone());
        }
        List<Map<String, Object>> teams = new ArrayList<>();
        if (e.getCaseKey() != null) {
            for (Incident i : incidentRepository.findByCaseKey(e.getCaseKey())) {
                if (i.getHospitalName() != null && !m.containsKey("hospital")) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("name", i.getHospitalName());
                    h.put("latitude", i.getHospitalLatitude());
                    h.put("longitude", i.getHospitalLongitude());
                    m.put("hospital", h);
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("team", i.getTeam().label());
                t.put("status", i.getStatus().name());
                t.put("unit", i.getAssignee() != null ? IncidentService.unitOf(i.getAssignee()) : null);
                t.put("etaMinutes", i.getEtaMinutes());
                teams.add(t);
            }
        }
        m.put("responders", teams);
        return m;
    }

    // ------------------------------------------------------------------ reaching contacts

    void notifyContacts(SosEvent e, String base, boolean falseAlarm) {
        User user = e.getUser();
        List<EmergencyContact> contacts = contactRepository.findByOwnerIdOrderByPositionAsc(user.getId());
        if (contacts.isEmpty()) {
            record(e, null, null, "IN_APP", "FAILED", "No emergency contacts saved");
            return;
        }
        String name = user.getName();
        String link = base + "/track.html?t=" + e.getTrackToken();
        String map = "https://maps.google.com/?q=" + fmt(e.getLatitude()) + "," + fmt(e.getLongitude());
        String theirPhone = user.getPhone() != null ? " Their phone: " + user.getPhone() + "." : "";

        String sms;
        String spoken;
        String inApp;
        if ("SHARE".equals(e.getKind()) && !falseAlarm) {
            sms = "Raksio: " + name + " is sharing their live location with you (not an emergency): " + link;
            spoken = null;
            inApp = "Location shared: " + firstName(name) + " is sharing their live location with you. " + link;
        } else if ("CHECKIN".equals(e.getKind()) && falseAlarm) {
            // The Safe Walk alert went out, then they tapped "I'm safe".
            sms = "Raksio: " + name + " has checked in - they are safe. Sorry for the worry.";
            spoken = null;
            inApp = "Safe Walk: " + firstName(name) + " has checked in and is safe.";
        } else if (falseAlarm) {
            sms = "Raksio: " + name + " is OK - the earlier emergency alert was a false alarm. Help has been called off.";
            spoken = null;
            inApp = "SOS cancelled: " + name + " says they are OK. The earlier alert was a false alarm.";
        } else {
            String what = what(e);
            sms = "RAKSIO EMERGENCY: " + name + " " + what + " " + helpLine(e) + " Live location: " + link
                    + " Map: " + map + "." + theirPhone + " Please call them now.";
            spoken = "This is an emergency call from Raksio. " + name + " " + what + " " + helpLine(e)
                    + " A text message with their location has been sent to this number. Please call " + name + " now.";
            inApp = "SOS: " + headline(e) + ". " + helpLine(e) + " Live location: " + link;
        }

        for (EmergencyContact c : contacts) {
            String who = c.getName() + (c.getRelation() != null ? " (" + c.getRelation() + ")" : "");
            // Inside Raksio
            List<User> appUsers = userRepository.findByPhone(c.getPhone()).stream()
                    .filter(u -> !u.getId().equals(user.getId()) && Boolean.TRUE.equals(u.getActive())).toList();
            for (User u : appUsers) {
                RiskLevel level = falseAlarm || "SHARE".equals(e.getKind()) ? RiskLevel.LOW
                        : "CHECKIN".equals(e.getKind()) ? RiskLevel.MEDIUM : RiskLevel.HIGH;
                alertService.raiseAboutIncident(u, AlertType.HIGH_RISK, level, inApp, e.getLatitude(), e.getLongitude(), null);
                record(e, who, c.getPhone(), "IN_APP", "SENT", "Raksio account: " + u.getName());
            }
            // SMS
            NotifyGateway.Result s = gateway.sms(c.getPhone(), sms);
            record(e, who, c.getPhone(), "SMS", s.status().name(), s.detail());
            // Voice call (not for the false-alarm notice)
            if (spoken != null) {
                NotifyGateway.Result call = gateway.call(c.getPhone(), spoken);
                record(e, who, c.getPhone(), "CALL", call.status().name(), call.detail());
            }
        }
    }

    /**
     * The ambulance picked a hospital (IncidentService.hospital): tell the
     * contacts where to go - in Raksio and by SMS (no call).
     */
    @EventListener
    public void onHospitalChosen(HospitalChosenEvent h) {
        SosEvent e = h.sosEventId() != null ? sosRepository.findById(h.sosEventId()).orElse(null) : null;
        if (e == null) return;
        pool.submit(() -> {
            try {
                User user = e.getUser();
                String map = h.latitude() != null && h.longitude() != null
                        ? " Map: https://maps.google.com/?q=" + fmt(h.latitude()) + "," + fmt(h.longitude()) : "";
                String sms = "Raksio update: " + user.getName() + " is being taken to " + h.hospitalName() + " by " + h.unit() + "." + map;
                String inApp = "SOS update: " + firstName(user.getName()) + " is being taken to " + h.hospitalName() + " by " + h.unit() + ".";
                for (EmergencyContact c : contactRepository.findByOwnerIdOrderByPositionAsc(user.getId())) {
                    String who = c.getName() + (c.getRelation() != null ? " (" + c.getRelation() + ")" : "");
                    for (User u : userRepository.findByPhone(c.getPhone())) {
                        if (u.getId().equals(user.getId()) || !Boolean.TRUE.equals(u.getActive())) continue;
                        alertService.raiseAboutIncident(u, AlertType.HIGH_RISK, RiskLevel.MEDIUM, inApp,
                                h.latitude() != null ? h.latitude() : e.getLatitude(), h.longitude() != null ? h.longitude() : e.getLongitude(), null);
                        record(e, who, c.getPhone(), "IN_APP", "SENT", "Hospital update");
                    }
                    NotifyGateway.Result s = gateway.sms(c.getPhone(), sms);
                    record(e, who, c.getPhone(), "SMS", s.status().name(), "Hospital update: " + (s.detail() != null ? s.detail() : ""));
                }
            } catch (Exception ex) {
                log.error("SOS {}: hospital update failed: {}", e.getId(), ex.toString());
            }
        });
    }

    private void record(SosEvent e, String name, String phone, String channel, String status, String detail) {
        deliveryRepository.save(SosDelivery.builder()
                .sosEventId(e.getId())
                .contactName(name)
                .contactPhone(phone)
                .channel(channel)
                .status(status)
                .detail(detail != null && detail.length() > 290 ? detail.substring(0, 290) : detail)
                .build());
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> view(SosEvent e, String base, boolean repeated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("kind", e.getKind());
        m.put("reason", e.getReason());
        m.put("status", e.getStatus());
        m.put("latitude", e.getLatitude());
        m.put("longitude", e.getLongitude());
        m.put("caseKey", e.getCaseKey());
        m.put("trackUrl", base + "/track.html?t=" + e.getTrackToken());
        m.put("createdAt", e.getCreatedAt());
        m.put("closedAt", e.getClosedAt());
        m.put("repeated", repeated);
        m.put("contactsCount", contactRepository.findByOwnerIdOrderByPositionAsc(e.getUser().getId()).size());
        m.put("smsReady", gateway.smsReady());
        m.put("callsReady", gateway.callsReady());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SosDelivery d : deliveryRepository.findBySosEventIdOrderByAtAsc(e.getId())) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("contactName", d.getContactName());
            r.put("contactPhone", d.getContactPhone());
            r.put("channel", d.getChannel());
            r.put("status", d.getStatus());
            r.put("detail", d.getDetail());
            r.put("at", d.getAt());
            rows.add(r);
        }
        m.put("deliveries", rows);
        return m;
    }

    private SosEvent own(User user, Long id) {
        SosEvent e = sosRepository.findById(id).orElseThrow(() -> ApiException.notFound("Alert not found"));
        if (!e.getUser().getId().equals(user.getId())) throw ApiException.notFound("Alert not found");
        return e;
    }

    /** Where they are now: the alert's own updates or their live location, whichever is newer. */
    private double[] latest(SosEvent e) {
        double lat = e.getLastLatitude() != null ? e.getLastLatitude() : e.getLatitude();
        double lng = e.getLastLongitude() != null ? e.getLastLongitude() : e.getLongitude();
        LocalDateTime best = lastSeenOwn(e);
        for (Vehicle v : vehicleRepository.findByOwnerId(e.getUser().getId())) {
            if (v.getCurrentLatitude() == null || v.getLastLocationUpdate() == null) continue;
            if (v.getLastLocationUpdate().isAfter(best)) {
                best = v.getLastLocationUpdate();
                lat = v.getCurrentLatitude();
                lng = v.getCurrentLongitude();
            }
        }
        return new double[]{lat, lng};
    }

    private LocalDateTime lastSeen(SosEvent e) {
        LocalDateTime best = lastSeenOwn(e);
        for (Vehicle v : vehicleRepository.findByOwnerId(e.getUser().getId())) {
            if (v.getLastLocationUpdate() != null && v.getLastLocationUpdate().isAfter(best)) best = v.getLastLocationUpdate();
        }
        return best;
    }

    private static LocalDateTime lastSeenOwn(SosEvent e) {
        return e.getLastSeenAt() != null ? e.getLastSeenAt() : e.getCreatedAt();
    }

    static String what(SosEvent e) {
        if ("SHARE".equals(e.getKind())) return "is sharing their live location with you.";
        if ("CHECKIN".equals(e.getKind())) {
            return "started a Safe Walk in Raksio and didn't check in on time.";
        }
        if ("CRASH".equals(e.getKind())) {
            return "may have been in a road accident" + (e.getSpeedKmh() != null && e.getSpeedKmh() > 0 ? " (was going about " + e.getSpeedKmh() + " km/h)" : "")
                    + " and did not answer Raksio's \"Are you OK?\".";
        }
        return switch (e.getReason() == null ? "ACCIDENT" : e.getReason()) {
            case "MEDICAL" -> "pressed SOS and needs an ambulance.";
            case "FIRE" -> "pressed SOS - there is a fire.";
            case "UNSAFE" -> "pressed SOS - they feel unsafe.";
            default -> "pressed SOS after a road accident.";
        };
    }

    static String helpLine(SosEvent e) {
        if ("SHARE".equals(e.getKind())) return "";
        if ("CHECKIN".equals(e.getKind())) return "If you can't reach them, call 112.";
        if ("CRASH".equals(e.getKind())) return "The police and an ambulance have been alerted.";
        return switch (e.getReason() == null ? "ACCIDENT" : e.getReason()) {
            case "MEDICAL" -> "An ambulance has been alerted.";
            case "FIRE" -> "The fire service has been alerted.";
            case "UNSAFE" -> "The police have been alerted.";
            default -> "The police and an ambulance have been alerted.";
        };
    }

    private static String headline(SosEvent e) {
        String first = firstName(e.getUser().getName());
        if ("SHARE".equals(e.getKind())) return first + " is sharing their live location";
        if ("CHECKIN".equals(e.getKind())) return first + " didn't check in from their Safe Walk";
        if ("CRASH".equals(e.getKind())) return first + " may have been in a road accident";
        return switch (e.getReason() == null ? "ACCIDENT" : e.getReason()) {
            case "MEDICAL" -> first + " needs an ambulance";
            case "FIRE" -> first + " reported a fire";
            case "UNSAFE" -> first + " feels unsafe";
            default -> first + " pressed SOS after an accident";
        };
    }

    private static String describe(SosEvent e, User user) {
        StringBuilder s = new StringBuilder();
        if ("CRASH".equals(e.getKind())) {
            s.append("Crash detected automatically by Raksio - the person did not answer for 30 seconds.");
            if (e.getSpeedKmh() != null && e.getSpeedKmh() > 0) s.append(" Was going about ").append(e.getSpeedKmh()).append(" km/h.");
        } else {
            s.append("SOS pressed in Raksio.");
        }
        if (user.getPhone() != null) s.append(" Phone: ").append(user.getPhone()).append('.');
        if (e.getDescription() != null) s.append(' ').append(e.getDescription());
        String out = s.toString();
        return out.length() > 500 ? out.substring(0, 500) : out;
    }

    static String firstName(String name) {
        if (name == null || name.isBlank()) return "Someone";
        return name.trim().split("\\s+")[0];
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.6f", d);
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String token() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
