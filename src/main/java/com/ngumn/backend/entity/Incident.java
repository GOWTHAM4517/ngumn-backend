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

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = IncidentStatus.OPEN;
        if (this.priority == null) this.priority = 0;
    }
}
