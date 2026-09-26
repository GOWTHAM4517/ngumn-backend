package com.ngumn.backend.repository;

import com.ngumn.backend.entity.Reward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewardRepository extends JpaRepository<Reward, Long> {
    List<Reward> findByUserIdOrderByCreatedAtDesc(Long userId);
}
