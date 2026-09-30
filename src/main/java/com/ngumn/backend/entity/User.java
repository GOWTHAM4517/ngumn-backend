package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String passwordSalt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    @Column(nullable = false)
    @Builder.Default
    private Integer rewardPoints = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    // What this person wants to be told about in the area around them
    // (Settings > Notifications in the app). Empty = the defaults: everything
    // within 2 km - see NotificationPrefs.

    /** Tell me about new reports and rule-breakers within this many metres. */
    @Column(name = "alert_radius_m")
    private Integer alertRadiusM;

    /** Tell me when someone reports a hazard near me. */
    @Column(name = "notify_hazards")
    private Boolean notifyHazards;

    /** Tell me when someone near me breaks a road rule or is reported for one. */
    @Column(name = "notify_rule_breakers")
    private Boolean notifyRuleBreakers;

    /**
     * Police, ambulance, municipality or electricity - set only by an admin
     * (Admin dashboard > Responders), never at sign-up. Null for citizens.
     * The app shows responders the help requests for their team.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "responder_team", length = 20, columnDefinition = "varchar(20)")
    private ResponderTeam responderTeam;

    /** A responder's unit as people see it: "Traffic Police - Benz Circle", "108 Ambulance - Unit 4". */
    @Column(name = "unit_name", length = 120)
    private String unitName;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.rewardPoints == null) this.rewardPoints = 0;
        if (this.active == null) this.active = true;
    }
}
