package com.ngumn.backend.repository;

import com.ngumn.backend.entity.LocationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocationLogRepository extends JpaRepository<LocationLog, Long> {
    List<LocationLog> findTop50ByVehicleIdOrderByTimestampDesc(Long vehicleId);
}
