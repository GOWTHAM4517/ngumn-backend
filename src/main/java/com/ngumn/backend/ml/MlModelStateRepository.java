package com.ngumn.backend.ml;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MlModelStateRepository extends JpaRepository<MlModelState, String> {
}
