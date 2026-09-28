package com.ngumn.backend.entity;

import com.ngumn.backend.util.ReportLifetime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A road user's report that someone broke a traffic rule (wrong-side
 * driving, jumping a signal, no helmet...).
 *
 * There is no admin in the loop: people nearby like it if it's true and
 * dislike it if it's wrong. Enough likes make it trusted - the reporter
 * earns points and, if the vehicle is on NGUMN, its driver is alerted and
 * the violation is added to their driving record. More than two dislikes
 * take it down and cost the reporter points. See CommunityService for the
 * thresholds.
 *
 * It's shown to people nearby for LIFETIME (ReportLifetime.COMPLAINT)
 * after it's made - the vehicle is long gone by then - unless the reporter
 * removes it first, or two people say it's not an issue anymore.
 *
 * The vehicle can be an NGUMN vehicle picked in the app
 * (accusedVehicle), a typed number plate, or unknown.
 */
@Entity
@Table(name = "violation_complaints")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ViolationComplaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30)")
    private ViolationType type;

    @ManyToOne
    @JoinColumn(name = "accused_vehicle_id")
    private Vehicle accusedVehicle;

    @Column(length = 20)
    private String plateNumber;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    @Builder.Default
    private ReportStatus status = ReportStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private Integer confirmations = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer denials = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;

    /** People who liked the report (thumbs up - "helpful"). Nullable so Hibernate can add it to an existing table. */
    @Builder.Default
    private Integer helpfulCount = 0;

    /** People who disliked it (thumbs down). Nullable so Hibernate can add it to an existing table. */
    @Builder.Default
    private Integer dislikeCount = 0;

    /** Comments on it. Nullable so Hibernate can add it to an existing table. */
    @Builder.Default
    private Integer commentCount = 0;

    /** People who said it's not an issue anymore. Nullable so Hibernate can add it to an existing table. */
    @Builder.Default
    private Integer goneCount = 0;

    /** When it was taken down early - by the reporter, an admin or the community - or null. */
    private LocalDateTime clearedAt;

    /** REPORTER, ADMIN or COMMUNITY. */
    @Column(length = 20)
    private String clearedBy;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = ReportStatus.PENDING;
        if (this.confirmations == null) this.confirmations = 0;
        if (this.denials == null) this.denials = 0;
        if (this.helpfulCount == null) this.helpfulCount = 0;
        if (this.dislikeCount == null) this.dislikeCount = 0;
        if (this.commentCount == null) this.commentCount = 0;
        if (this.goneCount == null) this.goneCount = 0;
    }

    /** When it stops being shown to people nearby. */
    public LocalDateTime effectiveExpiresAt() {
        LocalDateTime made = createdAt != null ? createdAt : LocalDateTime.now();
        return made.plus(ReportLifetime.COMPLAINT);
    }

    /** Still shown to people nearby: not taken down by dislikes, not cleared, not expired. */
    public boolean isActiveAt(LocalDateTime now) {
        return status != ReportStatus.REJECTED && clearedAt == null && effectiveExpiresAt().isAfter(now);
    }

    /** Why it's no longer shown - REJECTED, CLEARED or EXPIRED - or null while it is. */
    public String endReasonAt(LocalDateTime now) {
        if (status == ReportStatus.REJECTED) return "REJECTED";
        if (clearedAt != null) return "CLEARED";
        if (!effectiveExpiresAt().isAfter(now)) return "EXPIRED";
        return null;
    }
}
