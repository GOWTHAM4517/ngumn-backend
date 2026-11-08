package com.ngumn.backend.repository;

import com.ngumn.backend.entity.MarketListing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface MarketListingRepository extends JpaRepository<MarketListing, Long> {
    List<MarketListing> findByStatusIn(Collection<String> statuses);

    List<MarketListing> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    List<MarketListing> findByPlateKeyAndStatusIn(String plateKey, Collection<String> statuses);

    List<MarketListing> findByCarIdAndStatusIn(Long carId, Collection<String> statuses);

    long countBySellerIdAndStatusIn(Long sellerId, Collection<String> statuses);

    long countBySellerIdAndStatus(Long sellerId, String status);
}
