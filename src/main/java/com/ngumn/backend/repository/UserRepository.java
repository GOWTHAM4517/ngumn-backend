package com.ngumn.backend.repository;

import com.ngumn.backend.entity.ResponderTeam;
import com.ngumn.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /** A team's responder accounts that can sign in. */
    List<User> findByResponderTeamAndActiveTrue(ResponderTeam team);

    /** Every responder account (for the admin dashboard). */
    List<User> findByResponderTeamIsNotNull();

    /** Accounts with this mobile number (an emergency contact who also uses Raksio). */
    List<User> findByPhone(String phone);

    /** Admins - told when an emergency waits too long or a complaint misses its deadline. */
    List<User> findByRoleAndActiveTrue(com.ngumn.backend.entity.Role role);

    /** Asked to become a responder and waiting for an admin. */
    List<User> findByRequestStatus(String requestStatus);
}
