package com.ngumn.backend.repository;

import com.ngumn.backend.entity.ListingSighting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ListingSightingRepository extends JpaRepository<ListingSighting, Long> {
    boolean existsByListingIdAndBuyerIdAndAtAfter(Long listingId, Long buyerId, LocalDateTime after);

    long countByBuyerIdAndAtAfter(Long buyerId, LocalDateTime after);

    List<ListingSighting> findByListingId(Long listingId);
}
