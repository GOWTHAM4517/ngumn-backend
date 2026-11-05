package com.ngumn.backend.repository;

import com.ngumn.backend.entity.CarPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface CarPositionRepository extends JpaRepository<CarPosition, Long> {
    List<CarPosition> findByCarIdAndAtAfterOrderByAtAsc(Long carId, LocalDateTime after);

    List<CarPosition> findByCarIdAndAtBetweenOrderByAtAsc(Long carId, LocalDateTime from, LocalDateTime to);

    @Transactional
    long deleteByAtBefore(LocalDateTime before);

    @Transactional
    long deleteByCarId(Long carId);
}
