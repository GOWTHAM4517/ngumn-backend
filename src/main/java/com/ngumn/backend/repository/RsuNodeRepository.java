package com.ngumn.backend.repository;

import com.ngumn.backend.entity.RsuNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RsuNodeRepository extends JpaRepository<RsuNode, Long> {
    Optional<RsuNode> findByRsuCode(String rsuCode);
}
