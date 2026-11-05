package com.ngumn.backend.repository;

import com.ngumn.backend.entity.CarSighting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface CarSightingRepository extends JpaRepository<CarSighting, Long> {
    List<CarSighting> findByCarIdOrderByAtDesc(Long carId);

    List<CarSighting> findByCarIdAndAtAfterOrderByAtDesc(Long carId, LocalDateTime after);

    boolean existsByCarIdAndReporterIdAndAtAfter(Long carId, Long reporterId, LocalDateTime after);
}
