package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One person's one-tap feedback on a report: "Helpful" (the heart) or
 * "Not there anymore". At most one of each kind per person per report
 * (unique key), so tapping twice can't count twice.
 *
 * Works for hazard reports and rule-breaker reports alike (targetType,
 * like CommunityVote). Enum columns are plain varchar so new values can
 * be added later without a manual migration.
 */
@Entity
@Table(name = "report_feedback",
        uniqueConstraints = @UniqueConstraint(columnNames = {"target_type", "target_id", "user_id", "kind"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20, columnDefinition = "varchar(20)")
    private VoteTarget targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20, columnDefinition = "varchar(20)")
    private FeedbackKind kind;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }
}
