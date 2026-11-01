package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A crash Raksio detected (nobody tapped "I'm OK") or an SOS someone pressed.
 * It makes the help requests (police, ambulance, fire...) and tells every
 * emergency contact - see SosService. Contacts get a live-location link
 * (/track.html?t=trackToken) that works without the app.
 */
@Entity
@Table(name = "sos_events", indexes = {
        @Index(name = "idx_sos_user", columnList = "user_id"),
        @Index(name = "idx_sos_token", columnList = "track_token", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SosEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** CRASH (detected) or SOS (pressed). */
    @Column(nullable = false, length = 10)
    private String kind;

    /** For an SOS: ACCIDENT, MEDICAL, FIRE or UNSAFE. */
    @Column(length = 20)
    private String reason;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    /** Latest known position (from the person's live location while it's active). */
    private Double lastLatitude;
    private Double lastLongitude;
    private LocalDateTime lastSeenAt;

    /** About how fast they were going just before (crash). */
    private Integer speedKmh;

    @Column(length = 500)
    private String description;

    /** ACTIVE, CANCELLED ("I'm OK") or CLOSED. */
    @Column(nullable = false, length = 12)
    private String status;

    /** The help requests it made share this caseKey. */
    @Column(name = "case_key", length = 40)
    private String caseKey;

    /** Secret part of the live-location link sent to contacts. */
    @Column(name = "track_token", nullable = false, length = 40)
    private String trackToken;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime closedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = "ACTIVE";
    }

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
