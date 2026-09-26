package com.ngumn.backend.repository;

import com.ngumn.backend.entity.EmergencyEvent;
import com.ngumn.backend.entity.EmergencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmergencyEventRepository extends JpaRepository<EmergencyEvent, Long> {
    List<EmergencyEvent> findByStatus(EmergencyStatus status);
    Optional<EmergencyEvent> findFirstByVehicleIdAndStatus(Long vehicleId, EmergencyStatus status);
}
