package com.ngumn.backend.repository;

import com.ngumn.backend.entity.TrafficViolation;
import com.ngumn.backend.entity.ViolationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TrafficViolationRepository extends JpaRepository<TrafficViolation, Long> {
    List<TrafficViolation> findTop100ByDriverIdOrderByCreatedAtDesc(Long driverId);
    List<TrafficViolation> findTop100ByOrderByCreatedAtDesc();
    Optional<TrafficViolation> findTopByVehicleIdAndTypeOrderByCreatedAtDesc(Long vehicleId, ViolationType type);
    long countByCreatedAtAfter(LocalDateTime since);
}
