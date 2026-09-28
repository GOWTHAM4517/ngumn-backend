package com.ngumn.backend.repository;

import com.ngumn.backend.entity.RoadReport;
import com.ngumn.backend.entity.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Stores just the comment count. Saving the whole report instead could
     * undo a vote or a "not there anymore" made at the same moment (it
     * would write back the older copy of the report the comment started
     * with).
     */
    @Modifying
    @Transactional
    @Query("update RoadReport r set r.commentCount = :newCount where r.id = :id")
    int updateCommentCount(@Param("id") Long id, @Param("newCount") Integer newCount);

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
