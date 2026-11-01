package com.ngumn.backend.repository;

import com.ngumn.backend.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByToken(String token);
    void deleteByToken(String token);

    /** Every saved sign-in of an account (to sign it out everywhere after a password change). */
    java.util.List<AuthToken> findByUserId(Long userId);
}
