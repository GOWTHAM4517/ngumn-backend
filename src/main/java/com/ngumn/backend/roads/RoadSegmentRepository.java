package com.ngumn.backend.roads;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoadSegmentRepository extends JpaRepository<RoadSegment, Long> {
    long countByMaxspeedKmhNotNull();

    long countByOnewayNot(Integer oneway);
}
