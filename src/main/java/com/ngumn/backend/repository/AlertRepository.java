package com.ngumn.backend.repository;

import com.ngumn.backend.entity.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    List<Alert> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Alert> findTop100ByUserIdOrderByCreatedAtDesc(Long userId);
    /** Anything newer than the last one the phone has seen - what the app checks for while it's in the background. */
    List<Alert> findTop20ByUserIdAndIdGreaterThanOrderByIdAsc(Long userId, Long id);
    List<Alert> findByVehicleIdOrderByCreatedAtDesc(Long vehicleId);
    List<Alert> findTop100ByOrderByCreatedAtDesc();
}
