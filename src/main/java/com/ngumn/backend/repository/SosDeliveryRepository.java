package com.ngumn.backend.repository;

import com.ngumn.backend.entity.SosDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SosDeliveryRepository extends JpaRepository<SosDelivery, Long> {
    List<SosDelivery> findBySosEventIdOrderByAtAsc(Long sosEventId);
}
