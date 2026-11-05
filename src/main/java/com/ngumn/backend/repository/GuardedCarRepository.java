package com.ngumn.backend.repository;

import com.ngumn.backend.entity.GuardedCar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GuardedCarRepository extends JpaRepository<GuardedCar, Long> {
    List<GuardedCar> findByOwnerIdOrderByCreatedAtAsc(Long ownerId);

    Optional<GuardedCar> findByTrackerId(String trackerId);

    Optional<GuardedCar> findByTrackToken(String trackToken);

    List<GuardedCar> findByStatus(String status);

    long countByOwnerId(Long ownerId);
}
