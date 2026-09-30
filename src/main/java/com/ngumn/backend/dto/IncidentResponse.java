package com.ngumn.backend.dto;

import com.ngumn.backend.entity.Incident;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A help request as the app shows it. The person who asked sees who's
 * coming; responders also see the reporter's name (never their email or
 * phone) so they can find them at the spot.
 */
@Data
@AllArgsConstructor
public class IncidentResponse {
    private Long id;
    private String caseKey;
    private String type;
    /** "Vehicle broken down". */
    private String typeLabel;
    private String team;
    /** "Police". */
    private String teamLabel;
    private Double latitude;
    private Double longitude;
    private String vehicleKind;
    private String blocking;
    private Integer injured;
    private String description;
    private String status;
    /** How urgent right now - it grows while it waits. */
    private Integer priority;
    /** HIGH, MEDIUM or LOW (from priority). */
    private String urgency;
    /** For responders: the person who asked. */
    private String reporterName;
    private boolean mine;
    /** For responders: it's theirs. */
    private boolean assignedToMe;
    /** "Traffic Police - Benz Circle" once accepted. */
    private String responderUnit;
    private Integer etaMinutes;
    private String outcome;
    /** "The vehicle was moved to a safe spot". */
    private String outcomeLabel;
    private String note;
    /** From the position the app sent with the request (responder's queue), else null. */
    private Double distanceMeters;
    private LocalDateTime createdAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime arrivedAt;
    private LocalDateTime closedAt;

    public static IncidentResponse from(Incident i, Long viewerId, boolean responderView, int priorityNow,
                                        String urgency, Double distanceMeters) {
        return new IncidentResponse(
                i.getId(), i.getCaseKey(),
                i.getType().name(), i.getType().label(),
                i.getTeam().name(), i.getTeam().label(),
                i.getLatitude(), i.getLongitude(),
                i.getVehicleKind(), i.getBlocking(), i.getInjured(), i.getDescription(),
                i.getStatus().name(), priorityNow, urgency,
                responderView ? i.getReporter().getName() : null,
                i.getReporter().getId().equals(viewerId),
                i.getAssignee() != null && i.getAssignee().getId().equals(viewerId),
                i.getAssignee() != null ? unitOf(i.getAssignee()) : null,
                i.getEtaMinutes(),
                i.getOutcome() != null ? i.getOutcome().name() : null,
                i.getOutcome() != null ? i.getOutcome().label() : null,
                i.getNote(), distanceMeters,
                i.getCreatedAt(), i.getAcceptedAt(), i.getArrivedAt(), i.getClosedAt()
        );
    }

    private static String unitOf(com.ngumn.backend.entity.User u) {
        if (u.getUnitName() != null && !u.getUnitName().isBlank()) return u.getUnitName();
        return u.getResponderTeam() != null ? u.getResponderTeam().label() : u.getName();
    }
}
