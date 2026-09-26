package com.ngumn.backend.entity;

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

    @PrePersist
    protected void onCreate() {
        if (this.timestamp == null) this.timestamp = LocalDateTime.now();
        if (this.status == null) this.status = ReportStatus.PENDING;
        if (this.confirmations == null) this.confirmations = 0;
        if (this.denials == null) this.denials = 0;
    }
}
