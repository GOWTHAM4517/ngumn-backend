package com.ngumn.backend.repository;

import com.ngumn.backend.entity.ComplaintFiling;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ComplaintFilingRepository extends JpaRepository<ComplaintFiling, Long> {

    List<ComplaintFiling> findByIncidentIdOrderByCreatedAtAsc(Long incidentId);

    Optional<ComplaintFiling> findByToken(String token);

    List<ComplaintFiling> findByMethodAndCreatedAtAfter(String method, LocalDateTime after);
}
