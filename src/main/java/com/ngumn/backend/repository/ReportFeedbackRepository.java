package com.ngumn.backend.repository;

import com.ngumn.backend.entity.FeedbackKind;
import com.ngumn.backend.entity.ReportFeedback;
import com.ngumn.backend.entity.VoteTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReportFeedbackRepository extends JpaRepository<ReportFeedback, Long> {
    Optional<ReportFeedback> findByTargetTypeAndTargetIdAndUserIdAndKind(VoteTarget targetType, Long targetId,
                                                                        Long userId, FeedbackKind kind);
    long countByTargetTypeAndTargetIdAndKind(VoteTarget targetType, Long targetId, FeedbackKind kind);
    List<ReportFeedback> findByUserIdAndTargetTypeAndKind(Long userId, VoteTarget targetType, FeedbackKind kind);
    List<ReportFeedback> findByTargetTypeAndTargetId(VoteTarget targetType, Long targetId);
    /** A person's likes / dislikes that matched how the report turned out (for their trust score). */
    long countByUserIdAndOutcomeMatchedTrue(Long userId);
    long countByUserIdAndOutcomeMatchedFalse(Long userId);
}
