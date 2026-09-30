package com.ngumn.backend.service;

import com.ngumn.backend.dto.ResponderCreateRequest;
import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.UserRepository;
import com.ngumn.backend.security.PasswordUtil;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Police, ambulance and department accounts. Only an admin can make one
 * (Admin dashboard > Responders): sign-up in the app always makes a citizen
 * account, so nobody can pose as police.
 */
@Service
public class ResponderService {

    private final UserRepository userRepository;

    public ResponderService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Makes a responder account, or turns an existing account (same email) into one. */
    public Map<String, Object> createOrUpdate(User admin, ResponderCreateRequest r) {
        requireAdmin(admin);
        String email = r.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(email).orElse(null);
        boolean created = user == null;
        if (created) {
            if (r.getPassword() == null || r.getPassword().length() < 6) {
                throw ApiException.badRequest("Give the new account a password (at least 6 characters).");
            }
            String salt = PasswordUtil.generateSalt();
            user = User.builder()
                    .name(r.getName().trim())
                    .email(email)
                    .passwordHash(PasswordUtil.hash(r.getPassword(), salt))
                    .passwordSalt(salt)
                    .role(Role.DRIVER)
                    .rewardPoints(0)
                    .active(true)
                    .build();
        } else {
            user.setName(r.getName().trim());
            if (r.getPassword() != null && !r.getPassword().isBlank()) {
                String salt = PasswordUtil.generateSalt();
                user.setPasswordSalt(salt);
                user.setPasswordHash(PasswordUtil.hash(r.getPassword(), salt));
            }
        }
        user.setResponderTeam(r.getTeam());
        user.setUnitName(r.getUnitName().trim());
        user = userRepository.save(user);
        Map<String, Object> out = row(user);
        out.put("created", created);
        return out;
    }

    public List<Map<String, Object>> list(User admin) {
        requireAdmin(admin);
        return userRepository.findByResponderTeamIsNotNull().stream()
                .sorted(Comparator.comparing((User u) -> u.getResponderTeam().name()).thenComparing(User::getUnitName, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ResponderService::row).toList();
    }

    /** Back to a citizen account (the account itself stays). */
    public Map<String, Object> remove(User admin, Long id) {
        requireAdmin(admin);
        User user = userRepository.findById(id).orElseThrow(() -> ApiException.notFound("Account not found"));
        user.setResponderTeam(null);
        user.setUnitName(null);
        return row(userRepository.save(user));
    }

    private static Map<String, Object> row(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        m.put("team", u.getResponderTeam() != null ? u.getResponderTeam().name() : null);
        m.put("teamLabel", u.getResponderTeam() != null ? u.getResponderTeam().label() : null);
        m.put("unitName", u.getUnitName());
        m.put("active", u.getActive());
        return m;
    }

    private static void requireAdmin(User user) {
        if (user.getRole() != Role.ADMIN) throw ApiException.forbidden("Only admins can manage responder accounts.");
    }
}
