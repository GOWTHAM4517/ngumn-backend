package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;

/**
 * An official way to reach an authority about a complaint or a request for
 * help (ComplaintFilingService): the municipal corporation's grievance email,
 * the electricity helpline 1912, Andhra Pradesh's PGRS call centre 1100 and
 * portal, CPGRAMS, 112...
 *
 * <ul>
 *   <li>email + autoEmail: a new complaint is emailed there by Raksio itself
 *       (with a one-click reply link for the department);</li>
 *   <li>phone / whatsapp / sms / portalUrl: one tap in the app, with the
 *       complaint already written out.</li>
 * </ul>
 *
 * team null = every department; types null = every kind for that team.
 * centerLatitude/Longitude + radiusKm: only for things inside that circle (a
 * city's corporation); null = anywhere. Kept by the admin in the Control Centre.
 */
@Entity
@Table(name = "authority_channels")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthorityChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "Vijayawada Municipal Corporation", "AP grievance call centre (PGRS)". */
    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 20, columnDefinition = "varchar(20)")
    @Enumerated(EnumType.STRING)
    private ResponderTeam team;

    /** Comma-separated IncidentType names, e.g. "POTHOLE,ROAD_DIGGING"; blank = all of the team. */
    @Column(length = 400)
    private String types;

    /** CITY, STATE, NATIONAL or EMERGENCY - the order they're offered in. */
    @Column(nullable = false, length = 12)
    @Builder.Default
    private String level = "CITY";

    @Column(length = 120)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(length = 20)
    private String whatsapp;

    @Column(length = 20)
    private String sms;

    @Column(name = "portal_url", length = 200)
    private String portalUrl;

    /** Shown under the name: "Needs your Aadhaar-linked phone for the OTP". */
    @Column(length = 200)
    private String note;

    /** New complaints are emailed here automatically (once the person agrees to file it). */
    @Builder.Default
    private Boolean autoEmail = true;

    @Builder.Default
    private Boolean active = true;

    @Builder.Default
    private Integer sortOrder = 100;

    private Double centerLatitude;
    private Double centerLongitude;
    private Double radiusKm;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }

    /** For this kind of complaint / request? */
    public boolean covers(IncidentType type) {
        if (team != null && team != type.team()) return false;
        if (types == null || types.isBlank()) return true;
        return Arrays.stream(types.split(",")).map(s -> s.trim().toUpperCase(Locale.ROOT)).anyMatch(s -> s.equals(type.name()));
    }

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}
