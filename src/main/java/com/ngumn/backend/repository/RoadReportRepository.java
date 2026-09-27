package com.ngumn.backend.repository;

import com.ngumn.backend.entity.RoadReport;
import com.ngumn.backend.entity.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public interface RoadReportRepository extends JpaRepository<RoadReport, Long> {
    List<RoadReport> findByStatus(ReportStatus status);
    List<RoadReport> findByReporterIdOrderByTimestampDesc(Long reporterId);
    List<RoadReport> findTop100ByOrderByTimestampDesc();
    List<RoadReport> findByStatusAndTimestampAfter(ReportStatus status, LocalDateTime after);
    long countByReporterIdAndStatus(Long reporterId, ReportStatus status);
    List<RoadReport> findByExpiresAtIsNull();

    List<RoadReport> findByStatusNotAndClearedAtIsNullAndExpiresAtAfterOrderByTimestampDesc(ReportStatus status,
                                                                                          LocalDateTime now);

    List<RoadReport> findByStatusNotAndClearedAtIsNullAndExpiresAtIsNullAndTimestampAfter(ReportStatus status,
                                                                                         LocalDateTime after);

    /**
     * Reports that could still be on the road, newest first: not rejected,
     * not cleared, and either expiring after `now` or (rows from before
     * expiry was stored) made after `legacySince`. Callers re-check each
     * one with RoadReport.isActiveAt, which also applies the per-type
     * lifetime to those older rows.
     */
    default List<RoadReport> findPossiblyActive(ReportStatus rejected, LocalDateTime now, LocalDateTime legacySince) {
        List<RoadReport> out = new ArrayList<>(
                findByStatusNotAndClearedAtIsNullAndExpiresAtAfterOrderByTimestampDesc(rejected, now));
        out.addAll(findByStatusNotAndClearedAtIsNullAndExpiresAtIsNullAndTimestampAfter(rejected, legacySince));
        out.sort(Comparator.comparing(RoadReport::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return out;
    }
}
