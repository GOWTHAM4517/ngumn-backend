package com.ngumn.backend.repository;

import com.ngumn.backend.entity.MarketWish;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MarketWishRepository extends JpaRepository<MarketWish, Long> {
    List<MarketWish> findByUserIdOrderByCreatedAtAsc(Long userId);

    List<MarketWish> findByActiveTrue();
}
