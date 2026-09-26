package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One person's "true" / "false" answer about a hazard report or a
 * rule-breaker complaint. One vote per person per item (unique key).
 *
 * outcomeMatched is filled in when the item is decided: true if this vote
 * agreed with the community's final answer (those voters earn points and
 * trust), false if it didn't, null while voting is still open.
 */
@Entity
@Table(name = "community_votes",
        uniqueConstraints = @UniqueConstraint(columnNames = {"target_type", "target_id", "voter_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20, columnDefinition = "varchar(20)")
    private VoteTarget targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @ManyToOne
    @JoinColumn(name = "voter_id", nullable = false)
    private User voter;

    @Column(nullable = false)
    private Boolean agree;

    private Boolean outcomeMatched;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }
}
