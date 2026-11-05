package com.ngumn.backend.repository;

import com.ngumn.backend.entity.TestDrive;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestDriveRepository extends JpaRepository<TestDrive, Long> {
    Optional<TestDrive> findFirstByCarIdAndStatus(Long carId, String status);

    List<TestDrive> findTop20ByCarIdOrderByStartedAtDesc(Long carId);

    List<TestDrive> findByStatus(String status);
}
