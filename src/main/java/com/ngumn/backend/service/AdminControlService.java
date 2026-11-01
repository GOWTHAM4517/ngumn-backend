package com.ngumn.backend.service;

import com.ngumn.backend.dto.AdminUserUpdate;
import com.ngumn.backend.dto.BroadcastRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.notify.AuthorityForwarder;
import com.ngumn.backend.notify.NotifyGateway;
import com.ngumn.backend.repository.EmergencyContactRepository;
import com.ngumn.backend.repository.UserRepository;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.util.GeoUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * The admin's controls over people: every account, disabling, roles, making
 * responders, password resets, approving "I'm police / ambulance..." requests,
 * and broadcasting a message to everyone (or everyone in an area).
 */
@Service
public class AdminControlService {

    public static final String BROADCAST = "Message from Raksio";

    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final EmergencyContactRepository contactRepository;
    private final AuthService authService;
    private final AlertService alertService;
    private final NotifyGateway gateway;
    private final AuthorityForwarder forwarder;

    public AdminControlService(UserRepository userRepository, VehicleRepository vehicleRepository,
                               EmergencyContactRepository contactRepository, AuthService authService,
                               AlertService alertService, NotifyGateway gateway, AuthorityForwarder forwarder) {
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.contactRepository = contactRepository;
        this.authService = authService;
        this.alertService = alertService;
        this.gateway = gateway;
        this.forwarder = forwarder;
    }

    public static void requireAdmin(User user) {
        if (user.getRole() != Role.ADMIN) throw ApiException.forbidden("Only admins can do this.");
    }

    /** filter: all, citizens, responders, admins, disabled, requests. q matches name, email or phone. */
    public List<Map<String, Object>> users(User admin, String q, String filter) {
        requireAdmin(admin);
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String f = filter == null ? "all" : filter.trim().toLowerCase(Locale.ROOT);
        return userRepository.findAll().stream()
                .filter(u -> !u.getEmail().endsWith("@ngumn.local") || u.getRole() == Role.ADMIN || !f.equals("citizens"))
                .filter(u -> switch (f) {
                    case "citizens" -> u.getResponderTeam() == null && u.getRole() != Role.ADMIN;
                    case "responders" -> u.getResponderTeam() != null;
                    case "admins" -> u.getRole() == Role.ADMIN;
                    case "disabled" -> !Boolean.TRUE.equals(u.getActive());
                    case "requests" -> "PENDING".equals(u.getRequestStatus());
                    default -> true;
                })
                .filter(u -> query.isEmpty()
                        || u.getName().toLowerCase(Locale.ROOT).contains(query)
                        || u.getEmail().toLowerCase(Locale.ROOT).contains(query)
                        || (u.getPhone() != null && u.getPhone().contains(query))
                        || (u.getUnitName() != null && u.getUnitName().toLowerCase(Locale.ROOT).contains(query)))
                .sorted(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(500)
                .map(this::row).toList();
    }

    @Transactional
    public Map<String, Object> update(User admin, Long id, AdminUserUpdate r) {
        requireAdmin(admin);
        User u = userRepository.findById(id).orElseThrow(() -> ApiException.notFound("Account not found"));
        boolean self = u.getId().equals(admin.getId());
        if (r.getActive() != null) {
            if (self && !r.getActive()) throw ApiException.badRequest("You can't disable your own admin account.");
            u.setActive(r.getActive());
        }
        if (r.getRole() != null) {
            if (self && r.getRole() != Role.ADMIN) throw ApiException.badRequest("You can't remove your own admin role.");
            u.setRole(r.getRole());
        }
        if (Boolean.TRUE.equals(r.getClearTeam())) {
            u.setResponderTeam(null);
            u.setUnitName(null);
        } else if (r.getTeam() != null) {
            String unit = r.getUnitName() != null && !r.getUnitName().isBlank() ? r.getUnitName().trim()
                    : u.getUnitName() != null ? u.getUnitName() : r.getTeam().label();
            u.setResponderTeam(r.getTeam());
            u.setUnitName(unit.length() > 120 ? unit.substring(0, 120) : unit);
            u.setRequestStatus(null);
        } else if (r.getUnitName() != null && u.getResponderTeam() != null && !r.getUnitName().isBlank()) {
            u.setUnitName(r.getUnitName().trim());
        }
        userRepository.save(u);
        if (r.getNewPassword() != null && !r.getNewPassword().isBlank()) {
            if (r.getNewPassword().length() < 8) throw ApiException.badRequest("Use at least 8 characters for the password.");
            authService.setPassword(u, r.getNewPassword(), null);
        }
        return row(u);
    }

    /** Approve an "I'm police / ambulance..." request: the account becomes a responder on that team. */
    @Transactional
    public Map<String, Object> decide(User admin, Long id, boolean approve, String unitOverride) {
        requireAdmin(admin);
        User u = userRepository.findById(id).orElseThrow(() -> ApiException.notFound("Account not found"));
        if (!"PENDING".equals(u.getRequestStatus()) || u.getRequestedTeam() == null) {
            throw ApiException.badRequest("No request waiting for this account.");
        }
        ResponderTeam team = u.getRequestedTeam();
        String unit = unitOverride != null && !unitOverride.isBlank() ? unitOverride.trim() : u.getRequestedUnit();
        if (approve) {
            u.setResponderTeam(team);
            u.setUnitName(unit);
            u.setRequestStatus(null);
        } else {
            u.setRequestStatus("REJECTED");
        }
        userRepository.save(u);
        alertService.raiseAboutIncident(u, AlertType.HIGH_RISK, RiskLevel.LOW,
                IncidentService.CLOSED + ": " + (approve
                        ? "Your " + team.label() + " account is approved (" + unit + "). Sign out and back in to see your team's requests."
                        : "Your request for a " + team.label() + " account wasn't approved. Contact the Raksio admin if this is a mistake."),
                null, null, null);
        return row(u);
    }

    /** A message to everyone - or everyone whose live position is within radiusMeters of a point. */
    public Map<String, Object> broadcast(User admin, BroadcastRequest r) {
        requireAdmin(admin);
        RiskLevel level = parseLevel(r.getLevel());
        boolean area = r.getLatitude() != null && r.getLongitude() != null && r.getRadiusMeters() != null && r.getRadiusMeters() > 0;
        Set<Long> targets = new LinkedHashSet<>();
        if (area) {
            LocalDateTime fresh = LocalDateTime.now().minusHours(6);
            for (Vehicle v : vehicleRepository.findAll()) {
                if (v.getOwner() == null || Boolean.TRUE.equals(v.getIsSimulated()) || v.getCurrentLatitude() == null) continue;
                if (v.getLastLocationUpdate() == null || v.getLastLocationUpdate().isBefore(fresh)) continue;
                if (GeoUtil.distanceMeters(r.getLatitude(), r.getLongitude(), v.getCurrentLatitude(), v.getCurrentLongitude()) <= r.getRadiusMeters()) {
                    targets.add(v.getOwner().getId());
                }
            }
        } else {
            for (User u : userRepository.findAll()) {
                if (Boolean.TRUE.equals(u.getActive()) && !u.getEmail().endsWith("@ngumn.local")) targets.add(u.getId());
            }
        }
        int sent = 0;
        for (Long id : targets) {
            User u = userRepository.findById(id).orElse(null);
            if (u == null || !Boolean.TRUE.equals(u.getActive())) continue;
            alertService.raiseAboutIncident(u, AlertType.HIGH_RISK, level, BROADCAST + ": " + r.getMessage().trim(),
                    area ? r.getLatitude() : null, area ? r.getLongitude() : null, null);
            sent++;
        }
        return Map.of("sent", sent, "area", area);
    }

    /** Which outside channels really work on this server. */
    public Map<String, Object> channels(User admin) {
        requireAdmin(admin);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contacts", gateway.describe());
        m.put("authorities", forwarder.describe());
        return m;
    }

    /** Admin sends a test SMS / call to check the provider. */
    public Map<String, Object> test(User admin, String phone, boolean call) {
        requireAdmin(admin);
        String to = ContactService.normalise(phone);
        NotifyGateway.Result res = call
                ? gateway.call(to, "This is a test call from Raksio. Emergency calls to contacts are working.")
                : gateway.sms(to, "Raksio test message: SMS alerts to emergency contacts are working.");
        return Map.of("to", to, "channel", call ? "CALL" : "SMS", "status", res.status().name(), "detail", String.valueOf(res.detail()));
    }

    private Map<String, Object> row(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        m.put("phone", u.getPhone());
        m.put("role", u.getRole().name());
        m.put("team", u.getResponderTeam() != null ? u.getResponderTeam().name() : null);
        m.put("teamLabel", u.getResponderTeam() != null ? u.getResponderTeam().label() : null);
        m.put("unitName", u.getUnitName());
        m.put("active", u.getActive());
        m.put("rewardPoints", u.getRewardPoints());
        m.put("contacts", contactRepository.findByOwnerIdOrderByPositionAsc(u.getId()).size());
        m.put("requestedTeam", u.getRequestedTeam() != null ? u.getRequestedTeam().name() : null);
        m.put("requestedUnit", u.getRequestedUnit());
        m.put("requestNote", u.getRequestNote());
        m.put("requestStatus", u.getRequestStatus());
        m.put("requestedAt", u.getRequestedAt());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }

    private static RiskLevel parseLevel(String s) {
        if (s == null) return RiskLevel.MEDIUM;
        try {
            return RiskLevel.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RiskLevel.MEDIUM;
        }
    }
}
