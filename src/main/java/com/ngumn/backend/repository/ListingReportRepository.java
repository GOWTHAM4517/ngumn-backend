package com.ngumn.backend.repository;

import com.ngumn.backend.entity.ListingReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ListingReportRepository extends JpaRepository<ListingReport, Long> {
    boolean existsByListingIdAndReporterId(Long listingId, Long reporterId);

    List<ListingReport> findByListingIdOrderByCreatedAtDesc(Long listingId);
}
