package com.ngumn.backend.repository;

import com.ngumn.backend.entity.SosEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SosEventRepository extends JpaRepository<SosEvent, Long> {
    Optional<SosEvent> findByTrackToken(String trackToken);

    List<SosEvent> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime after);

    List<SosEvent> findByUserIdAndStatus(Long userId, String status);
}
