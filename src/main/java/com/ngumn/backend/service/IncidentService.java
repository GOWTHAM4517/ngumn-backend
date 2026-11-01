package com.ngumn.backend.service;

import com.ngumn.backend.dto.AdminIncidentAction;
import com.ngumn.backend.dto.EmergencyStartRequest;
import com.ngumn.backend.dto.IncidentRequest;
import com.ngumn.backend.dto.IncidentResponse;
import com.ngumn.backend.dto.IncidentUpdateRequest;
import com.ngumn.backend.dto.RatingRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.notify.AuthorityForwarder;
import com.ngumn.backend.repository.IncidentRepository;
import com.ngumn.backend.repository.UserRepository;
import com.ngumn.backend.repository.VehicleRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Help requests (see Incident): someone asks for help, it goes to the team
 * that deals with it, one responder takes it and moves it along, and the
 * person who asked hears about every step.
 *
 * <pre>
 *   citizen: "My lorry broke down, it's blocking a lane"
 *     -> police queue, ranked by how much it gets in the way
 *   police: Accept (ETA from where they are) -> "Help is on the way"
 *   police: At the scene                      -> "Help has arrived"
 *   police: Resolved - moved to a safe spot   -> "Help request resolved"
 * </pre>
 *
 * Everyone on the team is told about a new request (a phone notification
 * through their alerts). Only one of them can accept it.
 *
 * Messages to people start with a fixed headline ("Help is on the way: ...")
 * - the app uses it as the notification title (see HEADLINES).
 */
@Service
public class IncidentService {

    // Headlines - the app matches these exactly (src/lib/alert-text.ts).
    public static final String NEW_REQUEST = "New help request";
    public static final String ON_THE_WAY = "Help is on the way";
    public static final String ARRIVED = "Help has arrived";
    public static final String RESOLVED = "Help request resolved";
    public static final String CLOSED = "Help request closed";
    public static final String STILL_LOOKING = "Still finding help";
    public static final String CANCELLED = "Help request cancelled";
    /** Waited too long - raised again with the team and the admins (EscalationService). */
    public static final String ESCALATED = "Request escalated";
    /** A department has started on a complaint. */
    public static final String TAKEN_UP = "Complaint taken up";
    public static final List<String> HEADLINES = List.of(NEW_REQUEST, ON_THE_WAY, ARRIVED, RESOLVED, CLOSED, STILL_LOOKING, CANCELLED,
            ESCALATED, TAKEN_UP);

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Set<String> VEHICLE_KINDS = Set.of("BIKE", "CYCLE", "AUTO", "CAR", "BUS", "TRUCK", "OTHER");
    static final Set<String> BLOCKING = Set.of("YES", "PARTLY", "NO");
    /** A responder's queue covers the last 12 hours (older open requests have gone stale). */
    static final Duration QUEUE_WINDOW = Duration.ofHours(12);
    /** "My requests" goes back a month (complaints can take days). */
    static final Duration MINE_WINDOW = Duration.ofDays(30);
    /** Complaints stay in a department's list while open, up to this old. */
    static final Duration COMPLAINT_WINDOW = Duration.ofDays(30);
    /** Others reporting the same complaint this close by make it more urgent. */
    static final double SAME_COMPLAINT_M = 150;
    /** For the ETA: roughly how fast a responder gets through city traffic. */
    static final double RESPONDER_KMH = 30;
    public static final int HIGH = 70;
    public static final int MEDIUM = 45;

    private final IncidentRepository incidentRepository;
    private final UserRepository userRepository;
    private final AlertService alertService;
    private final NgumnWebSocketHandler webSocketHandler;
    private final VehicleRepository vehicleRepository;
    private final EmergencyService emergencyService;
    private final AuthorityForwarder forwarder;
    private final ExecutorService forwardPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "authority-forward");
        t.setDaemon(true);
        return t;
    });

    public IncidentService(IncidentRepository incidentRepository, UserRepository userRepository,
                           AlertService alertService, NgumnWebSocketHandler webSocketHandler,
                           VehicleRepository vehicleRepository, EmergencyService emergencyService,
                           AuthorityForwarder forwarder) {
        this.incidentRepository = incidentRepository;
        this.userRepository = userRepository;
        this.alertService = alertService;
        this.webSocketHandler = webSocketHandler;
        this.vehicleRepository = vehicleRepository;
        this.emergencyService = emergencyService;
        this.forwarder = forwarder;
    }

    @PreDestroy
    void stopForwarding() {
        forwardPool.shutdown();
    }

    /** Now in India - for peak hours. (A method so checks can pick the time.) */
    protected ZonedDateTime nowIst() {
        return ZonedDateTime.now(IST);
    }

    protected LocalDateTime now() {
        return LocalDateTime.now();
    }

    // ------------------------------------------------------------------ asking for help

    /**
     * Makes the request - two for an accident with people hurt (police and
     * an ambulance). Asking again for the same thing while it's still open
     * returns the open one instead of a duplicate.
     */
    @Transactional
    public List<IncidentResponse> create(User user, IncidentRequest r) {
        return create(user, r, "APP", null);
    }

    /** source: APP, CRASH, SOS or ADMIN; sosEventId when a crash / SOS made it. */
    @Transactional
    public List<IncidentResponse> create(User user, IncidentRequest r, String source, Long sosEventId) {
        if (r.getLatitude() < -90 || r.getLatitude() > 90 || r.getLongitude() < -180 || r.getLongitude() > 180) {
            throw ApiException.badRequest("That location doesn't look right.");
        }
        String vehicle = pick(r.getVehicleKind(), VEHICLE_KINDS, "vehicle");
        String blocking = pick(r.getBlocking(), BLOCKING, "blocking");
        int injured = r.getInjured() != null ? r.getInjured() : 0;
        String description = r.getDescription() != null && !r.getDescription().isBlank() ? r.getDescription().trim() : null;

        List<Incident> open = incidentRepository
                .findByReporterIdAndCreatedAtAfterOrderByCreatedAtDesc(user.getId(), now().minus(QUEUE_WINDOW)).stream()
                .filter(i -> i.getType() == r.getType() && i.getStatus().active())
                .toList();
        if (!open.isEmpty()) {
            String key = open.get(0).getCaseKey();
            return (key != null ? incidentRepository.findByCaseKey(key) : open.subList(0, 1)).stream()
                    .map(i -> view(user, i, null)).toList();
        }

        List<ResponderTeam> teams = teamsFor(r.getType(), injured);
        boolean complaint = r.getType().complaint();

        String caseKey = UUID.randomUUID().toString().substring(0, 12);
        int priority = priorityOf(r.getType(), vehicle, blocking, injured, nowIst());
        if (complaint) {
            // Several people complaining about the same thing nearby: it matters to more people.
            long others = similarOpen(r.getType(), r.getLatitude(), r.getLongitude(), user.getId());
            priority = Math.min(100, priority + (int) Math.min(20, others * 5));
        }
        List<Incident> made = new ArrayList<>();
        for (ResponderTeam team : teams) {
            Incident incident = incidentRepository.save(Incident.builder()
                    .reporter(user)
                    .type(r.getType())
                    .team(team)
                    .caseKey(caseKey)
                    .latitude(r.getLatitude())
                    .longitude(r.getLongitude())
                    .vehicleKind(vehicle)
                    .blocking(blocking)
                    .injured(r.getType() == IncidentType.ACCIDENT ? injured : null)
                    .description(description)
                    .status(IncidentStatus.OPEN)
                    .priority(priority)
                    .source(source != null ? source : "APP")
                    .sosEventId(sosEventId)
                    .dueAt(complaint ? now().plusHours(r.getType().slaHours()) : null)
                    .escalationLevel(0)
                    .build());
            made.add(incident);
            tellTeam(incident, user);
            changed(incident);
            forward(incident, complaint ? "New complaint" : NEW_REQUEST);
        }
        return made.stream().map(i -> view(user, i, null)).toList();
    }

    /**
     * Which teams a request goes to: its own, plus an ambulance for an accident
     * with people hurt, and the police for a fire (traffic and the scene).
     */
    public static List<ResponderTeam> teamsFor(IncidentType type, int injured) {
        List<ResponderTeam> teams = new ArrayList<>(List.of(type.team()));
        if (type == IncidentType.ACCIDENT && injured > 0) teams.add(ResponderTeam.AMBULANCE);
        if (type == IncidentType.FIRE) teams.add(ResponderTeam.POLICE);
        return teams;
    }

    /** Other people's open complaints of the same kind close by (last 30 days). */
    private long similarOpen(IncidentType type, double lat, double lng, Long exceptReporter) {
        return incidentRepository.findByTeamAndCreatedAtAfter(type.team(), now().minus(COMPLAINT_WINDOW)).stream()
                .filter(i -> i.getType() == type && i.getStatus().active())
                .filter(i -> !i.getReporter().getId().equals(exceptReporter))
                .filter(i -> GeoUtil.distanceMeters(lat, lng, i.getLatitude(), i.getLongitude()) <= SAME_COMPLAINT_M)
                .map(Incident::getCaseKey).distinct().count();
    }

    /** Someone's own requests from the last month, newest first. */
    public List<IncidentResponse> mine(User user) {
        return incidentRepository
                .findByReporterIdAndCreatedAtAfterOrderByCreatedAtDesc(user.getId(), now().minus(MINE_WINDOW)).stream()
                .map(i -> view(user, i, null)).toList();
    }

    /** The person who asked takes it back ("I'm fine now") - and the other half of an accident with it. */
    @Transactional
    public IncidentResponse cancel(User user, Long id) {
        Incident incident = find(id);
        if (!incident.getReporter().getId().equals(user.getId())) {
            throw ApiException.forbidden("Only the person who asked can cancel this request.");
        }
        List<Incident> group = incident.getCaseKey() != null ? incidentRepository.findByCaseKey(incident.getCaseKey()) : List.of(incident);
        for (Incident i : group) {
            if (!i.getStatus().active()) continue;
            User assignee = i.getAssignee();
            i.setStatus(IncidentStatus.CANCELLED);
            i.setClosedAt(now());
            endCorridor(i);
            incidentRepository.save(i);
            if (assignee != null) {
                note(assignee, i, CANCELLED, "The person who asked cancelled the request (" + summary(i) + "). You don't need to go.");
            }
            changed(i);
        }
        return view(user, find(id), null);
    }

    // ------------------------------------------------------------------ responders

    /**
     * A responder's list: theirs first, then the ones waiting (most urgent
     * first), then ones other units are handling, then what they closed in
     * the last 12 hours. With lat / lng (where they are) each has its
     * distance.
     */
    public List<IncidentResponse> queue(User responder, Double lat, Double lng) {
        ResponderTeam team = requireResponder(responder);
        LocalDateTime recent = now().minus(QUEUE_WINDOW);
        // Help requests: the last 12 hours. Complaints: while open (up to 30 days), so none is forgotten.
        List<Incident> rows = incidentRepository.findByTeamAndCreatedAtAfter(team, now().minus(COMPLAINT_WINDOW)).stream()
                .filter(i -> i.getCreatedAt().isAfter(recent)
                        || (i.getType().complaint() && i.getStatus().active())
                        || (i.getClosedAt() != null && i.getClosedAt().isAfter(recent)))
                .toList();
        Long me = responder.getId();

        Comparator<Incident> urgentFirst = Comparator.<Incident>comparingInt(this::priorityNow).reversed()
                .thenComparing(Incident::getCreatedAt);
        List<Incident> mineActive = rows.stream().filter(i -> i.getStatus().active() && isAssignee(i, me)).sorted(urgentFirst).toList();
        List<Incident> waiting = rows.stream().filter(i -> i.getStatus() == IncidentStatus.OPEN).sorted(urgentFirst).toList();
        List<Incident> others = rows.stream().filter(i -> i.getStatus().active() && i.getStatus() != IncidentStatus.OPEN && !isAssignee(i, me))
                .sorted(urgentFirst).toList();
        List<Incident> done = rows.stream().filter(i -> !i.getStatus().active() && isAssignee(i, me))
                .sorted(Comparator.comparing((Incident i) -> i.getClosedAt() != null ? i.getClosedAt() : i.getCreatedAt()).reversed())
                .limit(10).toList();

        List<IncidentResponse> out = new ArrayList<>();
        for (List<Incident> part : List.of(mineActive, waiting, others, done)) {
            for (Incident i : part) {
                Double d = lat != null && lng != null ? GeoUtil.distanceMeters(lat, lng, i.getLatitude(), i.getLongitude()) : null;
                out.add(view(responder, i, d));
            }
        }
        return out;
    }

    /** "I'll take it" - with where the responder is, for the ETA. */
    @Transactional
    public IncidentResponse accept(User responder, Long id, Double lat, Double lng) {
        ResponderTeam team = requireResponder(responder);
        Incident incident = find(id);
        if (incident.getTeam() != team) throw ApiException.forbidden("This request is for the " + incident.getTeam().label() + ".");
        if (incident.getStatus() != IncidentStatus.OPEN) {
            if (isAssignee(incident, responder.getId())) return view(responder, incident, null);
            throw ApiException.badRequest(incident.getStatus().active()
                    ? "Already taken by " + unitOf(incident.getAssignee()) + "."
                    : "This request is already closed.");
        }
        incident.setStatus(IncidentStatus.ACCEPTED);
        incident.setAssignee(responder);
        incident.setAcceptedAt(now());
        Integer eta = null;
        if (lat != null && lng != null) {
            double km = GeoUtil.distanceMeters(lat, lng, incident.getLatitude(), incident.getLongitude()) / 1000.0;
            eta = (int) Math.max(2, Math.round(km / RESPONDER_KMH * 60) + 2);
        }
        if (incident.getType().complaint()) {
            incident.setEtaMinutes(null);
            incidentRepository.save(incident);
            note(incident.getReporter(), incident, TAKEN_UP,
                    unitOf(responder) + " has taken up your " + incident.getType().noun()
                            + (incident.getDueAt() != null ? ". It should be fixed by " + incident.getDueAt().format(DUE) + "." : "."));
        } else {
            incident.setEtaMinutes(eta);
            startCorridor(incident, responder);
            incidentRepository.save(incident);
            note(incident.getReporter(), incident, ON_THE_WAY,
                    unitOf(responder) + " is coming for your " + incident.getType().noun()
                            + (eta != null ? " - about " + eta + " min." : ".")
                            + safetyTip(incident));
        }
        changed(incident);
        return view(responder, incident, null);
    }

    /** At the scene / resolved / false report / hand it back (OPEN). Only the responder who took it. */
    @Transactional
    public IncidentResponse update(User responder, Long id, IncidentUpdateRequest r) {
        requireResponder(responder);
        Incident incident = find(id);
        if (!isAssignee(incident, responder.getId())) throw ApiException.forbidden("Accept this request first.");
        if (!incident.getStatus().active()) throw ApiException.badRequest("This request is already closed.");
        String note = r.getNote() != null && !r.getNote().isBlank() ? r.getNote().trim() : null;
        if (note != null) incident.setNote(note);
        String unit = unitOf(responder);
        String extra = note != null ? " \"" + note + "\"" : "";

        switch (r.getStatus()) {
            case AT_SCENE -> {
                incident.setStatus(IncidentStatus.AT_SCENE);
                incident.setArrivedAt(now());
                incidentRepository.save(incident);
                note(incident.getReporter(), incident, ARRIVED, unit + " is at the spot." + extra);
            }
            case RESOLVED -> {
                if (r.getOutcome() == null) throw ApiException.badRequest("Say what was done.");
                incident.setStatus(IncidentStatus.RESOLVED);
                incident.setOutcome(r.getOutcome());
                incident.setClosedAt(now());
                if (incident.getArrivedAt() == null) incident.setArrivedAt(now());
                endCorridor(incident);
                incidentRepository.save(incident);
                note(incident.getReporter(), incident, RESOLVED, r.getOutcome().label() + " (" + unit + ")." + extra
                        + " Rate how it was handled in Raksio.");
            }
            case FALSE_REPORT -> {
                incident.setStatus(IncidentStatus.FALSE_REPORT);
                incident.setClosedAt(now());
                endCorridor(incident);
                incidentRepository.save(incident);
                note(incident.getReporter(), incident, CLOSED, unit + " found nothing that needed help at the spot." + extra);
            }
            case OPEN -> {
                // Handing it back - someone else on the team can take it.
                incident.setStatus(IncidentStatus.OPEN);
                incident.setAssignee(null);
                incident.setEtaMinutes(null);
                incident.setAcceptedAt(null);
                incident.setArrivedAt(null);
                endCorridor(incident);
                incidentRepository.save(incident);
                note(incident.getReporter(), incident, STILL_LOOKING,
                        unit + " can't come after all - your request is back with the " + incident.getTeam().label() + "." + extra);
                tellTeam(incident, responder);
            }
            default -> throw ApiException.badRequest("That's not a step a responder can take.");
        }
        changed(incident);
        return view(responder, incident, null);
    }

    // ------------------------------------------------------------------ viewing

    /** One request: for the person who asked, a responder on its team, or an admin. */
    public IncidentResponse get(User user, Long id, Double lat, Double lng) {
        Incident incident = find(id);
        boolean reporter = incident.getReporter().getId().equals(user.getId());
        boolean team = user.getResponderTeam() == incident.getTeam();
        if (!reporter && !team && user.getRole() != Role.ADMIN) throw ApiException.notFound("Help request not found");
        Double d = lat != null && lng != null ? GeoUtil.distanceMeters(lat, lng, incident.getLatitude(), incident.getLongitude()) : null;
        return view(user, incident, d);
    }

    IncidentResponse view(User viewer, Incident i, Double distance) {
        boolean responderView = viewer.getResponderTeam() == i.getTeam() || viewer.getRole() == Role.ADMIN;
        int p = priorityNow(i);
        return IncidentResponse.from(i, viewer.getId(), responderView, p, urgencyOf(p), distance, now());
    }

    // ------------------------------------------------------------------ rating

    /** The person who asked rates a resolved request or complaint (1-5 stars). */
    @Transactional
    public IncidentResponse rate(User user, Long id, RatingRequest r) {
        Incident incident = find(id);
        if (!incident.getReporter().getId().equals(user.getId())) throw ApiException.forbidden("Only the person who asked can rate it.");
        if (incident.getStatus() != IncidentStatus.RESOLVED) throw ApiException.badRequest("You can rate it once it's resolved.");
        incident.setRating(r.getStars());
        incident.setRatingComment(r.getComment() != null && !r.getComment().isBlank() ? r.getComment().trim() : null);
        incidentRepository.save(incident);
        if (incident.getAssignee() != null) {
            note(incident.getAssignee(), incident, RESOLVED, "Rated " + r.getStars() + "/5 by the person who asked ("
                    + summary(incident) + ")" + (incident.getRatingComment() != null ? ": \"" + incident.getRatingComment() + "\"" : "."));
        }
        changed(incident);
        return view(user, incident, null);
    }

    // ------------------------------------------------------------------ admin control

    /** Every request and complaint in the last `days` (newest / most urgent first), optionally one team or status. */
    public List<IncidentResponse> adminList(User admin, ResponderTeam team, String status, boolean complaints, boolean help, int days) {
        requireAdmin(admin);
        LocalDateTime since = now().minusDays(Math.max(1, Math.min(days, 90)));
        Set<String> statuses = new HashSet<>();
        if (status != null && !status.isBlank()) {
            if (status.equalsIgnoreCase("ACTIVE")) statuses.addAll(List.of("OPEN", "ACCEPTED", "AT_SCENE"));
            else statuses.add(status.trim().toUpperCase(Locale.ROOT));
        }
        return incidentRepository.findByCreatedAtAfter(since).stream()
                .filter(i -> team == null || i.getTeam() == team)
                .filter(i -> statuses.isEmpty() || statuses.contains(i.getStatus().name()))
                .filter(i -> (complaints && i.getType().complaint()) || (help && !i.getType().complaint()))
                .sorted(Comparator.comparing((Incident i) -> !i.getStatus().active())
                        .thenComparing(Comparator.comparingInt(this::priorityNow).reversed())
                        .thenComparing(Incident::getCreatedAt, Comparator.reverseOrder()))
                .limit(500)
                .map(i -> view(admin, i, null)).toList();
    }

    /**
     * Admin takes charge: assign to a responder of the same team, or change
     * the status (close, resolve, mark false, reopen). Everyone involved is told.
     */
    @Transactional
    public IncidentResponse adminAct(User admin, Long id, AdminIncidentAction a) {
        requireAdmin(admin);
        Incident incident = find(id);
        String note = a.getNote() != null && !a.getNote().isBlank() ? a.getNote().trim() : null;
        if (note != null) incident.setNote(note.length() > 300 ? note.substring(0, 300) : note);
        String extra = note != null ? " \"" + note + "\"" : "";

        if (a.getResponderId() != null) {
            User responder = userRepository.findById(a.getResponderId()).orElseThrow(() -> ApiException.notFound("Responder not found"));
            if (responder.getResponderTeam() != incident.getTeam()) {
                throw ApiException.badRequest(responder.getName() + " isn't on the " + incident.getTeam().label() + " team.");
            }
            User before = incident.getAssignee();
            incident.setAssignee(responder);
            if (incident.getStatus() == IncidentStatus.OPEN || !incident.getStatus().active()) {
                incident.setStatus(IncidentStatus.ACCEPTED);
                incident.setClosedAt(null);
            }
            if (incident.getAcceptedAt() == null) incident.setAcceptedAt(now());
            incidentRepository.save(incident);
            note(responder, incident, NEW_REQUEST, "The admin assigned this to you: " + summary(incident) + "." + extra
                    + " Open Raksio to see where it is.");
            if (before != null && !before.getId().equals(responder.getId())) {
                note(before, incident, CLOSED, "The admin moved " + summary(incident) + " to " + unitOf(responder) + ".");
            }
            note(incident.getReporter(), incident, incident.getType().complaint() ? TAKEN_UP : ON_THE_WAY,
                    unitOf(responder) + " has been assigned to your " + incident.getType().noun() + "." + extra);
        }

        if (a.getStatus() != null) {
            IncidentStatus to = a.getStatus();
            switch (to) {
                case RESOLVED -> {
                    incident.setStatus(IncidentStatus.RESOLVED);
                    incident.setOutcome(a.getOutcome() != null ? a.getOutcome() : IncidentOutcome.SITUATION_HANDLED);
                    incident.setClosedAt(now());
                    endCorridor(incident);
                    note(incident.getReporter(), incident, RESOLVED, incident.getOutcome().label() + " (closed by the Raksio admin)." + extra);
                }
                case FALSE_REPORT, CANCELLED -> {
                    incident.setStatus(to);
                    incident.setClosedAt(now());
                    endCorridor(incident);
                    note(incident.getReporter(), incident, CLOSED, "The Raksio admin closed your " + incident.getType().noun() + "." + extra);
                }
                case OPEN -> {
                    incident.setStatus(IncidentStatus.OPEN);
                    incident.setAssignee(null);
                    incident.setAcceptedAt(null);
                    incident.setArrivedAt(null);
                    incident.setClosedAt(null);
                    incident.setOutcome(null);
                    endCorridor(incident);
                    if (incident.getType().complaint() && (incident.getDueAt() == null || incident.getDueAt().isBefore(now()))) {
                        incident.setDueAt(now().plusHours(incident.getType().slaHours()));
                    }
                    tellTeam(incident, null);
                    note(incident.getReporter(), incident, STILL_LOOKING, "The Raksio admin reopened your " + incident.getType().noun() + "." + extra);
                }
                default -> throw ApiException.badRequest("The admin can resolve, close, mark false, cancel or reopen.");
            }
            if (incident.getAssignee() != null && to != IncidentStatus.OPEN) {
                note(incident.getAssignee(), incident, CLOSED, "The admin set " + summary(incident) + " to " + to.name().replace('_', ' ').toLowerCase(Locale.ROOT) + ".");
            }
            incidentRepository.save(incident);
        }
        changed(incident);
        return view(admin, incident, null);
    }

    // ------------------------------------------------------------------ escalation

    /**
     * Raises requests that have waited too long (run every minute by EscalationService):
     *
     * <ul>
     *   <li>Help request nobody accepted: after 5 min (urgent) / 10 min - the team
     *       again and every admin; after 15 min - admins again, the authority's
     *       control room again, and the person is told to call 112.</li>
     *   <li>Complaint nobody took up within a day (or half its deadline): the team
     *       and admins. Past its deadline: again, and the person is told it's been
     *       escalated. A whole deadline later: the admins once more.</li>
     * </ul>
     */
    @Transactional
    public int escalateDue() {
        LocalDateTime t = now();
        int n = 0;
        for (Incident i : incidentRepository.findByCreatedAtAfter(t.minus(COMPLAINT_WINDOW))) {
            if (!i.getStatus().active()) continue;
            int level = i.getEscalationLevel() != null ? i.getEscalationLevel() : 0;
            long waited = Duration.between(i.getCreatedAt(), t).toMinutes();
            if (!i.getType().complaint()) {
                if (i.getStatus() != IncidentStatus.OPEN || i.getCreatedAt().isBefore(t.minus(QUEUE_WINDOW))) continue;
                int first = priorityNow(i) >= HIGH ? 5 : 10;
                if (level == 0 && waited >= first) {
                    escalate(i, 1, "No unit has accepted it in " + waited + " min.", false, true);
                    n++;
                } else if (level == 1 && waited >= 15) {
                    escalate(i, 2, "Still nobody after " + waited + " min.", true, true);
                    note(i.getReporter(), i, STILL_LOOKING, "No unit has accepted your " + i.getType().noun()
                            + " yet - it has been raised with the admin. If it's urgent, call 112 now.");
                    n++;
                }
            } else {
                long sla = i.getType().slaHours() * 60L;
                boolean notTakenUp = i.getStatus() == IncidentStatus.OPEN;
                boolean overdue = i.getDueAt() != null && t.isAfter(i.getDueAt());
                if (level == 0 && notTakenUp && waited >= Math.min(24 * 60, Math.max(30, sla / 2))) {
                    escalate(i, 1, "Not taken up after " + hours(waited) + ".", false, true);
                    n++;
                } else if (level <= 1 && overdue) {
                    escalate(i, 2, "Past its deadline (" + i.getDueAt().format(DUE) + ").", true, true);
                    note(i.getReporter(), i, ESCALATED, "Your " + i.getType().noun() + " missed its deadline, so it has been raised with "
                            + "the Raksio admin and the department's control room.");
                    n++;
                } else if (level == 2 && i.getDueAt() != null && t.isAfter(i.getDueAt().plusMinutes(sla))) {
                    escalate(i, 3, "Still not fixed, " + hours(Duration.between(i.getDueAt(), t).toMinutes()) + " past its deadline.", true, false);
                    n++;
                }
            }
        }
        return n;
    }

    private void escalate(Incident i, int level, String why, boolean forwardAgain, boolean tellTeamAgain) {
        i.setEscalationLevel(level);
        i.setLastEscalatedAt(now());
        incidentRepository.save(i);
        String text = summary(i) + " - " + why + (i.getAssignee() != null ? " With " + unitOf(i.getAssignee()) + "." : "");
        for (User admin : userRepository.findByRoleAndActiveTrue(Role.ADMIN)) {
            alertService.raiseAboutIncident(admin, AlertType.HIGH_RISK, RiskLevel.HIGH, ESCALATED + ": " + text,
                    i.getLatitude(), i.getLongitude(), i.getId());
        }
        if (tellTeamAgain) {
            if (i.getAssignee() != null) note(i.getAssignee(), i, ESCALATED, text);
            else tellTeam(i, null);
        }
        if (forwardAgain) forward(i, ESCALATED);
        changed(i);
    }

    private static String hours(long minutes) {
        if (minutes < 120) return minutes + " min";
        if (minutes < 48 * 60) return (minutes / 60) + " hours";
        return (minutes / (60 * 24)) + " days";
    }

    // ------------------------------------------------------------------ authority systems and the Green Corridor

    /** Hands it on to the authority's own control room / system (in the background). */
    private void forward(Incident incident, String headline) {
        Long id = incident.getId();
        String summary = summary(incident);
        String map = "https://maps.google.com/?q=" + String.format(Locale.ROOT, "%.6f,%.6f", incident.getLatitude(), incident.getLongitude());
        if (forwarder.webhookFor(incident.getTeam()).isEmpty() && forwarder.smsNumbersFor(incident.getTeam()).isEmpty()) {
            incident.setForwardStatus("OFF");
            return;
        }
        forwardPool.submit(() -> {
            try {
                Incident fresh = incidentRepository.findById(id).orElse(null);
                if (fresh == null) return;
                String result = forwarder.forward(fresh, summary, map, headline);
                fresh.setForwardStatus(result.length() > 120 ? result.substring(0, 120) : result);
                incidentRepository.save(fresh);
            } catch (Exception e) {
                log.warn("Forwarding request {} failed: {}", id, e.toString());
            }
        });
    }

    /**
     * An ambulance or fire engine on its way: start the (simulated) Green
     * Corridor from its live position to the spot, so drivers ahead are asked
     * to give way. Needs the responder to be sharing live location.
     */
    private void startCorridor(Incident incident, User responder) {
        if (incident.getTeam() != ResponderTeam.AMBULANCE && incident.getTeam() != ResponderTeam.FIRE) return;
        try {
            Vehicle v = vehicleRepository.findByOwnerId(responder.getId()).stream()
                    .filter(x -> !Boolean.TRUE.equals(x.getIsSimulated()) && x.getCurrentLatitude() != null)
                    .max(Comparator.comparing(x -> x.getLastLocationUpdate() != null ? x.getLastLocationUpdate() : LocalDateTime.MIN))
                    .orElse(null);
            if (v == null) return;
            EmergencyStartRequest req = new EmergencyStartRequest();
            req.setVehicleId(v.getId());
            req.setOriginLatitude(v.getCurrentLatitude());
            req.setOriginLongitude(v.getCurrentLongitude());
            req.setDestinationLatitude(incident.getLatitude());
            req.setDestinationLongitude(incident.getLongitude());
            incident.setCorridorEventId(emergencyService.start(req).getId());
        } catch (RuntimeException e) {
            log.warn("Couldn't start the Green Corridor for request {}: {}", incident.getId(), e.toString());
        }
    }

    private void endCorridor(Incident incident) {
        Long eventId = incident.getCorridorEventId();
        if (eventId == null) return;
        incident.setCorridorEventId(null);
        try {
            emergencyService.end(eventId);
        } catch (RuntimeException e) {
            log.debug("Green Corridor {} already ended: {}", eventId, e.toString());
        }
    }

    private static void requireAdmin(User user) {
        if (user.getRole() != Role.ADMIN) throw ApiException.forbidden("Only admins can do this.");
    }

    // ------------------------------------------------------------------ priority

    /**
     * How urgent a request is when it's made (0-100): the kind of thing,
     * plus how much it blocks (a whole lane +25, partly +12), how big the
     * vehicle is (bus / lorry +15, car +8, auto +6, bike +2), people hurt
     * (+15 each, up to +45), and +10 for traffic trouble at peak hours
     * (8-11 am, 5-9 pm).
     */
    public static int priorityOf(IncidentType type, String vehicle, String blocking, int injured, ZonedDateTime when) {
        int p = type.basePriority();
        if ("YES".equals(blocking)) p += 25;
        else if ("PARTLY".equals(blocking)) p += 12;
        if (vehicle != null) {
            p += switch (vehicle) {
                case "BUS", "TRUCK" -> 15;
                case "CAR" -> 8;
                case "AUTO" -> 6;
                case "BIKE", "CYCLE" -> 2;
                default -> 4;
            };
        }
        p += Math.min(45, Math.max(0, injured) * 15);
        int hour = when.getHour();
        boolean peak = (hour >= 8 && hour < 11) || (hour >= 17 && hour < 21);
        if (type.traffic() && peak) p += 10;
        return Math.min(100, p);
    }

    /** Waiting makes it more urgent: +1 every 2 minutes it's open, up to +20. */
    int priorityNow(Incident i) {
        int p = i.getPriority() != null ? i.getPriority() : 0;
        if (i.getStatus() == IncidentStatus.OPEN && i.getCreatedAt() != null) {
            long minutes = Math.max(0, Duration.between(i.getCreatedAt(), now()).toMinutes());
            p += (int) Math.min(20, minutes / 2);
        }
        return Math.min(100, p);
    }

    public static String urgencyOf(int priority) {
        return priority >= HIGH ? "HIGH" : priority >= MEDIUM ? "MEDIUM" : "LOW";
    }

    // ------------------------------------------------------------------ helpers

    /** "Broken-down lorry - blocking a lane", "Accident - 2 people hurt". */
    public static String summary(Incident i) {
        StringBuilder s = new StringBuilder();
        String vehicle = vehicleWord(i.getVehicleKind());
        if (i.getType() == IncidentType.BREAKDOWN && vehicle != null) {
            s.append("Broken-down ").append(vehicle);
        } else {
            s.append(i.getType().label());
            if (vehicle != null && i.getType() == IncidentType.ACCIDENT) s.append(" (").append(vehicle).append(")");
        }
        if ("YES".equals(i.getBlocking())) s.append(" - blocking a lane");
        else if ("PARTLY".equals(i.getBlocking())) s.append(" - partly blocking the road");
        if (i.getInjured() != null && i.getInjured() > 0) {
            s.append(" - ").append(i.getInjured()).append(i.getInjured() == 1 ? " person hurt" : " people hurt");
        }
        return s.toString();
    }

    static String vehicleWord(String kind) {
        if (kind == null) return null;
        return switch (kind) {
            case "BIKE" -> "bike";
            case "CYCLE" -> "cycle";
            case "AUTO" -> "auto";
            case "CAR" -> "car";
            case "BUS" -> "bus";
            case "TRUCK" -> "lorry";
            default -> "vehicle";
        };
    }

    /** What to do while waiting - added to "Help is on the way". */
    static String safetyTip(Incident i) {
        return switch (i.getType()) {
            case BREAKDOWN -> " Keep your hazard lights on and wait off the road.";
            case ACCIDENT -> " Don't move anyone who is badly hurt unless they're in danger.";
            case UNSAFE -> " Stay somewhere busy and well lit.";
            case LIVE_WIRE -> " Keep everyone well away from the wire.";
            case FIRE -> " Move everyone well away and upwind. Don't go back for belongings.";
            default -> "";
        };
    }

    /** Everyone on the team (except whoever asked) hears about a request that's waiting. */
    private void tellTeam(Incident incident, User except) {
        String text = summary(incident) + (incident.getDescription() != null ? ". \"" + incident.getDescription() + "\"" : ".")
                + " Open Raksio to accept it.";
        RiskLevel level = priorityNow(incident) >= HIGH ? RiskLevel.HIGH : priorityNow(incident) >= MEDIUM ? RiskLevel.MEDIUM : RiskLevel.LOW;
        for (User responder : userRepository.findByResponderTeamAndActiveTrue(incident.getTeam())) {
            if (except != null && responder.getId().equals(except.getId())) continue;
            alertService.raiseAboutIncident(responder, AlertType.HIGH_RISK, level, NEW_REQUEST + ": " + text,
                    incident.getLatitude(), incident.getLongitude(), incident.getId());
        }
    }

    private void note(User to, Incident incident, String headline, String text) {
        alertService.raiseAboutIncident(to, AlertType.HIGH_RISK, RiskLevel.LOW, headline + ": " + text,
                incident.getLatitude(), incident.getLongitude(), incident.getId());
    }

    /** Tells connected apps a request changed, so queues and "My requests" refresh (no details - they fetch what they may see). */
    private void changed(Incident i) {
        webSocketHandler.broadcast("INCIDENT", Map.of("id", i.getId(), "team", i.getTeam().name(), "status", i.getStatus().name()));
    }

    private Incident find(Long id) {
        return incidentRepository.findById(id).orElseThrow(() -> ApiException.notFound("Help request not found"));
    }

    private static boolean isAssignee(Incident i, Long userId) {
        return i.getAssignee() != null && i.getAssignee().getId().equals(userId);
    }

    static String unitOf(User u) {
        if (u == null) return "Someone";
        if (u.getUnitName() != null && !u.getUnitName().isBlank()) return u.getUnitName();
        return u.getResponderTeam() != null ? u.getResponderTeam().label() : u.getName();
    }

    public static ResponderTeam requireResponder(User user) {
        if (user.getResponderTeam() == null) {
            throw ApiException.forbidden("Only police, ambulance and department accounts can do this.");
        }
        return user.getResponderTeam();
    }

    private static String pick(String value, Set<String> allowed, String what) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) throw ApiException.badRequest("Unknown " + what + ": " + value);
        return v;
    }

    /** For the admin dashboard: how many requests each team has waiting. */
    public Map<String, Long> openCounts() {
        LocalDateTime since = now().minus(QUEUE_WINDOW);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ResponderTeam t : ResponderTeam.values()) {
            counts.put(t.name(), incidentRepository.findByTeamAndCreatedAtAfter(t, since).stream()
                    .filter(i -> i.getStatus() == IncidentStatus.OPEN && !i.getType().complaint()).count());
        }
        LocalDateTime month = now().minus(COMPLAINT_WINDOW);
        List<Incident> recent = incidentRepository.findByCreatedAtAfter(month);
        counts.put("COMPLAINTS_OPEN", recent.stream().filter(i -> i.getType().complaint() && i.getStatus().active()).count());
        counts.put("COMPLAINTS_OVERDUE", recent.stream().filter(i -> i.getType().complaint() && i.getStatus().active()
                && i.getDueAt() != null && now().isAfter(i.getDueAt())).count());
        counts.put("ESCALATED", recent.stream().filter(i -> i.getStatus().active() && i.getEscalationLevel() != null && i.getEscalationLevel() > 0).count());
        return counts;
    }
}
