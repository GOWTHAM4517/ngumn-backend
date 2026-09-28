package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A comment on a hazard report or a rule-breaker report - "Still there at
 * 6 pm", "Cleared now, drove past it" - like comments under a post.
 *
 * Works for both kinds of report (targetType, like CommunityVote and
 * ReportFeedback). The target type is plain varchar so values can be
 * added later without a migration.
 */
@Entity
@Table(name = "report_comments", indexes = @Index(name = "idx_report_comments_target", columnList = "target_type, target_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportComment {

    public static final int MAX_LENGTH = 280;

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
    private User author;

    @Column(nullable = false, length = 300)
    private String text;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }
}
