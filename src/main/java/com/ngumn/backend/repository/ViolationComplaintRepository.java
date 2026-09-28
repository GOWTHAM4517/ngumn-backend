package com.ngumn.backend.repository;

import com.ngumn.backend.entity.ReportStatus;
import com.ngumn.backend.entity.ViolationComplaint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface ViolationComplaintRepository extends JpaRepository<ViolationComplaint, Long> {
    List<ViolationComplaint> findTop100ByOrderByCreatedAtDesc();
    List<ViolationComplaint> findByReporterIdOrderByCreatedAtDesc(Long reporterId);
    List<ViolationComplaint> findByStatusAndCreatedAtAfter(ReportStatus status, LocalDateTime after);
    long countByReporterIdAndStatus(Long reporterId, ReportStatus status);
    long countByReporterIdAndCreatedAtAfter(Long reporterId, LocalDateTime after);
    long countByStatus(ReportStatus status);

    /**
     * Stores just the comment count. Saving the whole complaint instead
     * could undo a vote made at the same moment (it would write back the
     * older copy the comment started with).
     */
    @Modifying
    @Transactional
    @Query("update ViolationComplaint c set c.commentCount = :newCount where c.id = :id")
    int updateCommentCount(@Param("id") Long id, @Param("newCount") Integer newCount);
}
