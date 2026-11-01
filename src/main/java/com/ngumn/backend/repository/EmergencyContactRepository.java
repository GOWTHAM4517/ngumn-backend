package com.ngumn.backend.repository;

import com.ngumn.backend.entity.EmergencyContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, Long> {
    List<EmergencyContact> findByOwnerIdOrderByPositionAsc(Long ownerId);

    /** People who have added this number as a contact (to show "You're X's emergency contact"). */
    List<EmergencyContact> findByPhone(String phone);
}
