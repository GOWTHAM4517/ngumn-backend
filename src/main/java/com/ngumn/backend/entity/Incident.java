package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A request for help - a broken-down vehicle, an accident, someone who feels
 * unsafe, an open manhole... - that goes to the team who deals with it
 * (police, ambulance, municipality, electricity) instead of just onto the
 * map. One responder accepts it and updates it until it's resolved, and the
 * person who asked is told at each step.
 *
 * An accident with people hurt is two requests with the same caseKey: one
 * for the police, one for an ambulance.
 *
 * Enum columns are plain varchar so new types can be added without an ALTER
 * TABLE (MySQL enum columns can't take new values on their own).
 */
@Entity
@Table(name = "incidents", indexes = {
        @Index(name = "idx_incident_team_status", columnList = "team,status"),
        @Index(name = "idx_incident_reporter", columnList = "reporter_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30)")
    private IncidentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private ResponderTeam team;

    /** Requests made together (police + ambulance for one accident) share this. */
    @Column(name = "case_key", length = 40)
    private String caseKey;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    /** For a breakdown or accident: BIKE, CAR, AUTO, BUS, TRUCK... (see IncidentService). */
    @Column(name = "vehicle_kind", length = 20)
    private String vehicleKind;

    /** Is it blocking traffic: YES (a whole lane), PARTLY, NO. */
    @Column(length = 10)
    private String blocking;

    /** How many people are hurt (accidents). */
    private Integer injured;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    @Builder.Default
    private IncidentStatus status = IncidentStatus.OPEN;

    /** The responder who took it. */
    @ManyToOne
    @JoinColumn(name = "assignee_id")
    private User assignee;

    /** The responder's estimate when accepting, in minutes. */
    @Column(name = "eta_minutes")
    private Integer etaMinutes;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, columnDefinition = "varchar(30)")
    private IncidentOutcome outcome;

    /** The responder's note to the person who asked ("Tow on its way, 20 min"). */
    @Column(length = 300)
    private String note;

    /** How urgent when it was made (a little more is added the longer it waits - see IncidentService). */
    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime acceptedAt;
    private LocalDateTime arrivedAt;
    private LocalDateTime closedAt;

    /** Where it came from: APP (asked by hand), CRASH (crash detection), SOS, ADMIN. */
    @Column(length = 12)
    private String source;

    /** The SOS / crash alert that made it, if any (see SosEvent). */
    @Column(name = "sos_event_id")
    private Long sosEventId;

    /** Complaints: when it should be fixed by (made + the type's slaHours). */
    @Column(name = "due_at")
    private LocalDateTime dueAt;

    /** How many times it has been raised again for waiting too long (EscalationService). */
    @Column(name = "escalation_level")
    private Integer escalationLevel;

    @Column(name = "last_escalated_at")
    private LocalDateTime lastEscalatedAt;

    /** The person who asked rates how it was handled (1-5) once it's resolved. */
    private Integer rating;

    @Column(name = "rating_comment", length = 300)
    private String ratingComment;

    /** Sent on to the authority's own control room / system (AuthorityForwarder): SENT, FAILED or OFF. */
    @Column(name = "forward_status", length = 120)
    private String forwardStatus;

    /** The Green Corridor started when an ambulance or fire engine took it (EmergencyEvent id). */
    @Column(name = "corridor_event_id")
    private Long corridorEventId;

    /** Ambulance: the hospital the person is being taken to. */
    @Column(name = "hospital_name", length = 120)
    private String hospitalName;

    private Double hospitalLatitude;
    private Double hospitalLongitude;

    /** Asked for by a responder already there ("Request backup") - the request it backs up. */
    @Column(name = "backup_of_id")
    private Long backupOfId;

    /**
     * Taken up outside Raksio - a department answering through the reply link
     * in its complaint email (ComplaintFilingService): "Vijayawada Municipal
     * Corporation". Shown as who's handling it when no Raksio unit is.
     */
    @Column(name = "external_handler", length = 120)
    private String externalHandler;

    /** The person agreed that departments may see their name and phone number (official filing). */
    @Column(name = "share_contact")
    private Boolean shareContact;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = IncidentStatus.OPEN;
        if (this.priority == null) this.priority = 0;
        if (this.escalationLevel == null) this.escalationLevel = 0;
        if (this.source == null) this.source = "APP";
    }
}
