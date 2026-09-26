package com.ngumn.backend.repository;

import com.ngumn.backend.entity.RiskAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiskAnalysisRepository extends JpaRepository<RiskAnalysis, Long> {
    List<RiskAnalysis> findTop20ByVehicleIdOrderByCreatedAtDesc(Long vehicleId);
}
