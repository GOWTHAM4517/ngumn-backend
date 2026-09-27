package com.ngumn.backend.entity;

import com.ngumn.backend.util.ReportLifetime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "road_reports")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoadReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportType type;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReportStatus status = ReportStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    // Community verification (see CommunityService): how many people
    // nearby answered "true" / "false". Nullable on purpose so Hibernate
    // can add these columns to an existing road_reports table - older
    // rows read as 0.
    @Builder.Default
    private Integer confirmations = 0;

    @Builder.Default
    private Integer denials = 0;

    /** When the report became VERIFIED or REJECTED. */
    private LocalDateTime decidedAt;

    // Reports clear by themselves (see ReportLifetime): expiresAt is set
    // when the report is made and pushed back each time someone confirms
    // it's still there. Nullable so Hibernate can add the column to an
    // existing table - rows from before read their expiry off the timestamp.
    private LocalDateTime expiresAt;

    /** Set when the report was cleared early - by its reporter, or after enough "not there anymore" taps. */
    private LocalDateTime clearedAt;

    /** Who cleared it: REPORTER, COMMUNITY or ADMIN (null while it's up). */
    @Column(length = 20, columnDefinition = "varchar(20)")
    private String clearedBy;

    /** People who marked it helpful (the heart). */
    @Builder.Default
    private Integer helpfulCount = 0;

    /** People who said "not there anymore". */
    @Builder.Default
    private Integer goneCount = 0;

    @PrePersist
    protected void onCreate() {
        if (this.timestamp == null) this.timestamp = LocalDateTime.now();
        if (this.status == null) this.status = ReportStatus.PENDING;
        if (this.confirmations == null) this.confirmations = 0;
        if (this.denials == null) this.denials = 0;
        if (this.helpfulCount == null) this.helpfulCount = 0;
        if (this.goneCount == null) this.goneCount = 0;
        if (this.expiresAt == null) this.expiresAt = this.timestamp.plus(ReportLifetime.of(this.type, this.description));
    }

    /** When it clears by itself - the stored time, or (older rows) worked out from when it was made. */
    public LocalDateTime effectiveExpiresAt() {
        if (expiresAt != null) return expiresAt;
        LocalDateTime made = timestamp != null ? timestamp : LocalDateTime.now();
        return made.plus(ReportLifetime.of(type, description));
    }

    /** Still on the road: not rejected by the community, not cleared, not expired. */
    public boolean isActiveAt(LocalDateTime now) {
        return status != ReportStatus.REJECTED && clearedAt == null && effectiveExpiresAt().isAfter(now);
    }

    /** Why it's no longer shown - REJECTED, CLEARED or EXPIRED - or null while it's up. */
    public String endReasonAt(LocalDateTime now) {
        if (status == ReportStatus.REJECTED) return "REJECTED";
        if (clearedAt != null) return "CLEARED";
        if (!effectiveExpiresAt().isAfter(now)) return "EXPIRED";
        return null;
    }
}
