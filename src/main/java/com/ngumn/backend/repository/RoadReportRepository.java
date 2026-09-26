package com.ngumn.backend.repository;

import com.ngumn.backend.entity.RoadReport;
import com.ngumn.backend.entity.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface RoadReportRepository extends JpaRepository<RoadReport, Long> {
    List<RoadReport> findByStatus(ReportStatus status);
    List<RoadReport> findByReporterIdOrderByTimestampDesc(Long reporterId);
    List<RoadReport> findTop100ByOrderByTimestampDesc();
    List<RoadReport> findByStatusAndTimestampAfter(ReportStatus status, LocalDateTime after);
    long countByReporterIdAndStatus(Long reporterId, ReportStatus status);
}
