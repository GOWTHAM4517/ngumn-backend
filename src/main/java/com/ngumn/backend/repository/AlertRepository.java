package com.ngumn.backend.repository;

import com.ngumn.backend.entity.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    List<Alert> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Alert> findByVehicleIdOrderByCreatedAtDesc(Long vehicleId);
    List<Alert> findTop100ByOrderByCreatedAtDesc();
}
