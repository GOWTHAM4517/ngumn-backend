package com.ngumn.backend.service;

import com.ngumn.backend.dto.AuthResponse;
import com.ngumn.backend.dto.LoginRequest;
import com.ngumn.backend.dto.RegisterRequest;
import com.ngumn.backend.entity.AuthToken;
import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.AuthTokenRepository;
import com.ngumn.backend.repository.UserRepository;
import com.ngumn.backend.security.PasswordUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;

    @Value("${ngumn.jwt.expiration-ms:86400000}")
    private long tokenExpirationMs;

    public AuthService(UserRepository userRepository, AuthTokenRepository authTokenRepository) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
    }

    public AuthResponse register(RegisterRequest request) {
        // Anyone on the internet can reach this, so nobody can make
        // themselves an admin: admin accounts are created on the server
        // (ADMIN_SEED_PASSWORD), never through sign-up.
        Role role = request.getRole() != null ? request.getRole() : Role.DRIVER;
        if (role == Role.ADMIN) {
            throw ApiException.badRequest("Admin accounts can't be created from the app.");
        }
        if (userRepository.existsByEmail(request.getEmail().toLowerCase())) {
            throw ApiException.badRequest("An account with this email already exists");
        }
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash(request.getPassword(), salt);

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail().toLowerCase())
                .passwordHash(hash)
                .passwordSalt(salt)
                .role(role)
                .rewardPoints(0)
                .active(true)
                .build();
        user = userRepository.save(user);

        return issueToken(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail().toLowerCase())
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));

        if (!PasswordUtil.matches(request.getPassword(), user.getPasswordSalt(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid email or password");
        }
        if (!Boolean.TRUE.equals(user.getActive())) {
            throw ApiException.forbidden("Account is disabled");
        }
        return issueToken(user);
    }

    private AuthResponse issueToken(User user) {
        String token = PasswordUtil.generateToken();
        LocalDateTime now = LocalDateTime.now();
        AuthToken authToken = AuthToken.builder()
                .token(token)
                .user(user)
                .issuedAt(now)
                .expiresAt(now.plusNanos(tokenExpirationMs * 1_000_000))
                .build();
        authTokenRepository.save(authToken);

        return new AuthResponse(token, user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getRewardPoints(),
                user.getResponderTeam(), user.getUnitName());
    }

    /**
     * Resolves the current user from an "Authorization: Bearer <token>"
     * header. Kept as an explicit call in each controller (rather than a
     * servlet filter) so the auth flow stays easy to read end-to-end for
     * the viva.
     */
    public User requireUser(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw ApiException.unauthorized("Missing or invalid Authorization header");
        }
        String token = authorizationHeader.substring(7).trim();
        AuthToken authToken = authTokenRepository.findByToken(token)
                .orElseThrow(() -> ApiException.unauthorized("Invalid or expired token"));

        if (authToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            authTokenRepository.delete(authToken);
            throw ApiException.unauthorized("Token expired, please log in again");
        }
        return authToken.getUser();
    }

    /** Checks the current password, sets the new one, and signs out every other device. */
    @org.springframework.transaction.annotation.Transactional
    public void changePassword(User user, String current, String next, String authorizationHeader) {
        User u = userRepository.findById(user.getId()).orElseThrow(() -> ApiException.unauthorized("Please sign in again"));
        if (!PasswordUtil.matches(current, u.getPasswordSalt(), u.getPasswordHash())) {
            throw ApiException.badRequest("Your current password is wrong.");
        }
        if (next == null || next.length() < 8) throw ApiException.badRequest("Use at least 8 characters.");
        if (next.equals(current)) throw ApiException.badRequest("Pick a password different from the current one.");
        String keep = authorizationHeader != null && authorizationHeader.startsWith("Bearer ") ? authorizationHeader.substring(7).trim() : null;
        setPassword(u, next, keep);
    }

    /**
     * Sets a password (admin reset or own change). Every other saved sign-in
     * for the account stops working; keepToken (this device) stays signed in.
     */
    @org.springframework.transaction.annotation.Transactional
    public void setPassword(User u, String password, String keepToken) {
        String salt = PasswordUtil.generateSalt();
        u.setPasswordSalt(salt);
        u.setPasswordHash(PasswordUtil.hash(password, salt));
        userRepository.save(u);
        authTokenRepository.deleteAll(authTokenRepository.findByUserId(u.getId()).stream()
                .filter(t -> keepToken == null || !keepToken.equals(t.getToken())).toList());
    }

    public void logout(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            authTokenRepository.deleteByToken(authorizationHeader.substring(7).trim());
        }
    }
}
