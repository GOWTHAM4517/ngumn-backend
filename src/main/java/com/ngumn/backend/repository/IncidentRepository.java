package com.ngumn.backend.repository;

import com.ngumn.backend.entity.Incident;
import com.ngumn.backend.entity.ResponderTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, Long> {
    /** A team's requests made since a time (the queue filters by status). */
    List<Incident> findByTeamAndCreatedAtAfter(ResponderTeam team, LocalDateTime after);

    /** Someone's own requests, newest first. */
    List<Incident> findByReporterIdAndCreatedAtAfterOrderByCreatedAtDesc(Long reporterId, LocalDateTime after);

    List<Incident> findByCaseKey(String caseKey);
}
