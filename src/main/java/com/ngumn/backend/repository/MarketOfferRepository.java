package com.ngumn.backend.repository;

import com.ngumn.backend.entity.MarketOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MarketOfferRepository extends JpaRepository<MarketOffer, Long> {
    List<MarketOffer> findByListingIdOrderByCreatedAtDesc(Long listingId);

    List<MarketOffer> findByBuyerIdOrderByCreatedAtDesc(Long buyerId);
}
