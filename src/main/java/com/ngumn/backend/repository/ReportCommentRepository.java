package com.ngumn.backend.repository;

import com.ngumn.backend.entity.ReportComment;
import com.ngumn.backend.entity.VoteTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ReportCommentRepository extends JpaRepository<ReportComment, Long> {
    /** The latest 100 comments on a report, newest first. */
    List<ReportComment> findTop100ByTargetTypeAndTargetIdOrderByIdDesc(VoteTarget targetType, Long targetId);
    long countByTargetTypeAndTargetId(VoteTarget targetType, Long targetId);
    long countByAuthorIdAndCreatedAtAfter(Long authorId, LocalDateTime after);
}
