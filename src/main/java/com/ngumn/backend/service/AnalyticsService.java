package com.ngumn.backend.service;

import com.ngumn.backend.entity.*;
import com.ngumn.backend.repository.IncidentRepository;
import com.ngumn.backend.repository.SosDeliveryRepository;
import com.ngumn.backend.repository.SosEventRepository;
import com.ngumn.backend.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin dashboard > Analytics: how fast each team responds, how many
 * complaints are fixed on time, what people need most and when, crash / SOS
 * numbers, ratings and accident black spots - over the last `days` days.
 * Plus the responder units on the map (who's on duty, where).
 */
@Service
public class AnalyticsService {

    private final IncidentRepository incidentRepository;
    private final SosEventRepository sosRepository;
    private final SosDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final IncidentService incidentService;
    private final HotspotService hotspotService;

    public AnalyticsService(IncidentRepository incidentRepository, SosEventRepository sosRepository,
                            SosDeliveryRepository deliveryRepository, UserRepository userRepository,
                            IncidentService incidentService, HotspotService hotspotService) {
        this.incidentRepository = incidentRepository;
        this.sosRepository = sosRepository;
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
        this.incidentService = incidentService;
        this.hotspotService = hotspotService;
    }

    public Map<String, Object> analytics(User admin, int days) {
        AdminControlService.requireAdmin(admin);
        int d = Math.max(1, Math.min(days, 365));
        LocalDateTime since = LocalDateTime.now().minusDays(d);
        List<Incident> all = incidentRepository.findByCreatedAtAfter(since);
        List<Incident> help = all.stream().filter(i -> !i.getType().complaint()).toList();
        List<Incident> complaints = all.stream().filter(i -> i.getType().complaint()).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("helpRequests", help.size());
        totals.put("complaints", complaints.size());
        totals.put("resolved", all.stream().filter(i -> i.getStatus() == IncidentStatus.RESOLVED).count());
        totals.put("open", all.stream().filter(i -> i.getStatus().active()).count());
        totals.put("falseReports", all.stream().filter(i -> i.getStatus() == IncidentStatus.FALSE_REPORT).count());
        totals.put("cancelled", all.stream().filter(i -> i.getStatus() == IncidentStatus.CANCELLED).count());
        totals.put("escalated", all.stream().filter(i -> i.getEscalationLevel() != null && i.getEscalationLevel() > 0).count());
        out.put("totals", totals);

        // Per team: how fast.
        List<Map<String, Object>> teams = new ArrayList<>();
        for (ResponderTeam t : ResponderTeam.values()) {
            List<Incident> rows = help.stream().filter(i -> i.getTeam() == t).toList();
            List<Incident> taken = rows.stream().filter(i -> i.getAcceptedAt() != null).toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team", t.name());
            m.put("label", t.label());
            m.put("requests", rows.size());
            m.put("resolved", rows.stream().filter(i -> i.getStatus() == IncidentStatus.RESOLVED).count());
            m.put("avgAcceptMin", avgMinutes(taken, Incident::getCreatedAt, Incident::getAcceptedAt));
            m.put("avgArriveMin", avgMinutes(rows.stream().filter(i -> i.getArrivedAt() != null && i.getAcceptedAt() != null).toList(),
                    Incident::getAcceptedAt, Incident::getArrivedAt));
            m.put("avgCloseMin", avgMinutes(rows.stream().filter(i -> i.getClosedAt() != null && i.getStatus() == IncidentStatus.RESOLVED).toList(),
                    Incident::getCreatedAt, Incident::getClosedAt));
            long within10 = taken.stream().filter(i -> Duration.between(i.getCreatedAt(), i.getAcceptedAt()).toMinutes() <= 10).count();
            m.put("acceptedWithin10Pct", taken.isEmpty() ? null : Math.round(100.0 * within10 / taken.size()));
            m.put("onDutyUnits", userRepository.findByResponderTeamAndActiveTrue(t).stream().filter(u -> Boolean.TRUE.equals(u.getOnDuty())).count());
            m.put("units", userRepository.findByResponderTeamAndActiveTrue(t).size());
            teams.add(m);
        }
        out.put("teams", teams);

        // Complaints: deadlines.
        List<Incident> fixed = complaints.stream().filter(i -> i.getStatus() == IncidentStatus.RESOLVED && i.getClosedAt() != null).toList();
        long onTime = fixed.stream().filter(i -> i.getDueAt() == null || !i.getClosedAt().isAfter(i.getDueAt())).count();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("total", complaints.size());
        c.put("fixed", fixed.size());
        c.put("fixedOnTimePct", fixed.isEmpty() ? null : Math.round(100.0 * onTime / fixed.size()));
        c.put("overdueOpen", complaints.stream().filter(i -> i.getStatus().active() && i.getDueAt() != null && LocalDateTime.now().isAfter(i.getDueAt())).count());
        c.put("avgFixHours", fixed.isEmpty() ? null
                : Math.round(fixed.stream().mapToLong(i -> Duration.between(i.getCreatedAt(), i.getClosedAt()).toMinutes()).average().orElse(0) / 6.0) / 10.0);
        out.put("complaints", c);

        // What people ask for most.
        Map<String, Long> byType = all.stream().collect(Collectors.groupingBy(i -> i.getType().label(), Collectors.counting()));
        out.put("byType", byType.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(12)
                .map(e -> Map.of("label", (Object) e.getKey(), "count", e.getValue())).toList());

        // When (hour of day, India time).
        int[] hours = new int[24];
        for (Incident i : all) hours[i.getCreatedAt().getHour()]++;
        out.put("byHour", Arrays.stream(hours).boxed().toList());

        // Ratings.
        List<Integer> ratings = all.stream().map(Incident::getRating).filter(Objects::nonNull).toList();
        out.put("ratings", Map.of("count", ratings.size(),
                "average", ratings.isEmpty() ? 0 : Math.round(ratings.stream().mapToInt(Integer::intValue).average().orElse(0) * 10) / 10.0));

        // Crash / SOS.
        List<SosEvent> everything = sosRepository.findByCreatedAtAfterOrderByCreatedAtDesc(since);
        // Sharing live location isn't an emergency - counted on its own.
        List<SosEvent> sos = everything.stream().filter(e -> !"SHARE".equals(e.getKind())).toList();
        long sent = 0, tried = 0;
        for (SosEvent e : sos) {
            for (SosDelivery dl : deliveryRepository.findBySosEventIdOrderByAtAsc(e.getId())) {
                tried++;
                if ("SENT".equals(dl.getStatus())) sent++;
            }
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", sos.size());
        s.put("crashes", sos.stream().filter(e -> "CRASH".equals(e.getKind())).count());
        s.put("sos", sos.stream().filter(e -> "SOS".equals(e.getKind())).count());
        s.put("missedCheckIns", sos.stream().filter(e -> "CHECKIN".equals(e.getKind())).count());
        s.put("falseAlarms", sos.stream().filter(e -> "CANCELLED".equals(e.getStatus()) && !"CHECKIN".equals(e.getKind())).count());
        s.put("locationShares", everything.size() - sos.size());
        s.put("contactAttempts", tried);
        s.put("contactAttemptsSent", sent);
        out.put("sos", s);

        out.put("hotspots", hotspotService.all(10));
        return out;
    }

    /** Every responder: team, unit, on duty, where they are now (last 2 hours), what they're handling. */
    public List<Map<String, Object>> units(User admin) {
        AdminControlService.requireAdmin(admin);
        List<Incident> recent = incidentRepository.findByCreatedAtAfter(LocalDateTime.now().minusDays(30));
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : userRepository.findByResponderTeamIsNotNull()) {
            if (!Boolean.TRUE.equals(u.getActive())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("name", u.getName());
            m.put("team", u.getResponderTeam().name());
            m.put("teamLabel", u.getResponderTeam().label());
            m.put("unitName", u.getUnitName());
            m.put("onDuty", Boolean.TRUE.equals(u.getOnDuty()));
            double[] at = incidentService.positionOf(u);
            m.put("latitude", at != null ? at[0] : null);
            m.put("longitude", at != null ? at[1] : null);
            m.put("handling", recent.stream().filter(i -> i.getStatus().active() && i.getAssignee() != null && i.getAssignee().getId().equals(u.getId())).count());
            out.add(m);
        }
        return out;
    }

    private interface Time {
        LocalDateTime of(Incident i);
    }

    /** Average minutes between two times, 1 decimal; null when there's nothing to average. */
    static Double avgMinutes(List<Incident> rows, Time from, Time to) {
        List<Long> secs = new ArrayList<>();
        for (Incident i : rows) {
            LocalDateTime a = from.of(i), b = to.of(i);
            if (a != null && b != null && !b.isBefore(a)) secs.add(Duration.between(a, b).toSeconds());
        }
        if (secs.isEmpty()) return null;
        return Math.round(secs.stream().mapToLong(Long::longValue).average().orElse(0) / 6.0) / 10.0;
    }
}
