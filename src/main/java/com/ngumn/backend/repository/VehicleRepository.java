package com.ngumn.backend.repository;

import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.entity.VehicleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
    Optional<Vehicle> findByVehicleCode(String vehicleCode);
    List<Vehicle> findByOwnerId(Long ownerId);
    List<Vehicle> findByStatus(VehicleStatus status);
    List<Vehicle> findByEmergencyStatusTrue();
}
