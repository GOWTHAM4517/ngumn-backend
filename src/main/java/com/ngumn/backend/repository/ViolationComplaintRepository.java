package com.ngumn.backend.repository;

import com.ngumn.backend.entity.ReportStatus;
import com.ngumn.backend.entity.ViolationComplaint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ViolationComplaintRepository extends JpaRepository<ViolationComplaint, Long> {
    List<ViolationComplaint> findTop100ByOrderByCreatedAtDesc();
    List<ViolationComplaint> findByReporterIdOrderByCreatedAtDesc(Long reporterId);
    List<ViolationComplaint> findByStatusAndCreatedAtAfter(ReportStatus status, LocalDateTime after);
    long countByReporterIdAndStatus(Long reporterId, ReportStatus status);
    long countByReporterIdAndCreatedAtAfter(Long reporterId, LocalDateTime after);
    long countByStatus(ReportStatus status);
}
