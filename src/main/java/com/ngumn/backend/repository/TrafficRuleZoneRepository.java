package com.ngumn.backend.repository;

import com.ngumn.backend.entity.TrafficRuleZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrafficRuleZoneRepository extends JpaRepository<TrafficRuleZone, Long> {
    List<TrafficRuleZone> findByActiveTrue();
    boolean existsByName(String name);
}
