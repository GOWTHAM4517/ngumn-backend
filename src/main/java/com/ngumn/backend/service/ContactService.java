package com.ngumn.backend.service;

import com.ngumn.backend.dto.ContactInput;
import com.ngumn.backend.dto.ResponderAccessRequest;
import com.ngumn.backend.entity.EmergencyContact;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.EmergencyContactRepository;
import com.ngumn.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Emergency contacts (at least 3, at most 5) and the person's own phone
 * number, plus asking to become a responder.
 *
 * Numbers are kept as +91XXXXXXXXXX so the same person always matches - that
 * is how a contact who also uses Raksio gets the alert inside the app.
 */
@Service
public class ContactService {

    public static final int MIN_CONTACTS = 3;
    public static final int MAX_CONTACTS = 5;

    private final EmergencyContactRepository contactRepository;
    private final UserRepository userRepository;

    public ContactService(EmergencyContactRepository contactRepository, UserRepository userRepository) {
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
    }

    /**
     * "98480 12345", "098480-12345", "+91 98480 12345", "919848012345" ->
     * "+919848012345". Numbers from other countries must start with "+".
     */
    public static String normalise(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        boolean plus = t.startsWith("+");
        String d = t.replaceAll("\\D", "");
        if (plus) {
            if (d.length() < 8 || d.length() > 15) throw ApiException.badRequest("\"" + raw + "\" doesn't look like a phone number.");
            return "+" + d;
        }
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        if (d.length() == 12 && d.startsWith("91")) d = d.substring(2);
        if (d.length() == 10 && "6789".indexOf(d.charAt(0)) >= 0) return "+91" + d;
        throw ApiException.badRequest("\"" + raw + "\" isn't a 10-digit Indian mobile number. For other countries start with +.");
    }

    public List<Map<String, Object>> list(User user) {
        return contactRepository.findByOwnerIdOrderByPositionAsc(user.getId()).stream().map(this::row).toList();
    }

    /** Replaces the whole list (calling order = list order). */
    @Transactional
    public List<Map<String, Object>> save(User user, List<ContactInput> input) {
        if (input == null || input.size() < MIN_CONTACTS) {
            throw ApiException.badRequest("Add at least " + MIN_CONTACTS + " emergency contacts.");
        }
        if (input.size() > MAX_CONTACTS) throw ApiException.badRequest("At most " + MAX_CONTACTS + " emergency contacts.");
        String mine = user.getPhone();
        Set<String> seen = new HashSet<>();
        List<EmergencyContact> rows = new ArrayList<>();
        int pos = 1;
        for (ContactInput c : input) {
            String name = c.getName() == null ? "" : c.getName().trim();
            if (name.isEmpty()) throw ApiException.badRequest("Every contact needs a name.");
            String phone = normalise(c.getPhone());
            if (phone.equals(mine)) throw ApiException.badRequest(name + "'s number is your own number.");
            if (!seen.add(phone)) throw ApiException.badRequest(name + "'s number is in the list twice.");
            String relation = c.getRelation() != null && !c.getRelation().isBlank() ? c.getRelation().trim() : null;
            rows.add(EmergencyContact.builder().owner(user).name(name).phone(phone).relation(relation).position(pos++).build());
        }
        contactRepository.deleteAll(contactRepository.findByOwnerIdOrderByPositionAsc(user.getId()));
        contactRepository.flush();
        contactRepository.saveAll(rows);
        return list(user);
    }

    /** People who have this user's number as an emergency contact (names only). */
    public List<String> contactOf(User user) {
        if (user.getPhone() == null) return List.of();
        return contactRepository.findByPhone(user.getPhone()).stream()
                .filter(c -> !c.getOwner().getId().equals(user.getId()))
                .map(c -> c.getOwner().getName()).distinct().toList();
    }

    @Transactional
    public Map<String, Object> setPhone(User user, String raw) {
        User u = userRepository.findById(user.getId()).orElseThrow();
        if (raw == null || raw.isBlank()) {
            u.setPhone(null);
        } else {
            String phone = normalise(raw);
            boolean taken = userRepository.findByPhone(phone).stream().anyMatch(o -> !o.getId().equals(u.getId()));
            if (taken) throw ApiException.badRequest("Another Raksio account already uses this number.");
            u.setPhone(phone);
        }
        userRepository.save(u);
        return profile(u);
    }

    public Map<String, Object> profile(User user) {
        User u = userRepository.findById(user.getId()).orElse(user);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phone", u.getPhone());
        m.put("role", u.getRole().name());
        m.put("responderTeam", u.getResponderTeam() != null ? u.getResponderTeam().name() : null);
        m.put("unitName", u.getUnitName());
        m.put("requestedTeam", u.getRequestedTeam() != null ? u.getRequestedTeam().name() : null);
        m.put("requestedUnit", u.getRequestedUnit());
        m.put("requestStatus", u.getRequestStatus());
        m.put("onDuty", Boolean.TRUE.equals(u.getOnDuty()));
        m.put("contactsCount", contactRepository.findByOwnerIdOrderByPositionAsc(u.getId()).size());
        m.put("minContacts", MIN_CONTACTS);
        m.put("contactOf", contactOf(u));
        return m;
    }

    @Transactional
    public Map<String, Object> requestResponder(User user, ResponderAccessRequest r) {
        User u = userRepository.findById(user.getId()).orElseThrow();
        if (u.getResponderTeam() != null) throw ApiException.badRequest("This account is already a " + u.getResponderTeam().label() + " account.");
        u.setRequestedTeam(r.getTeam());
        u.setRequestedUnit(r.getUnitName().trim());
        u.setRequestNote(r.getNote().trim());
        u.setRequestStatus("PENDING");
        u.setRequestedAt(LocalDateTime.now());
        userRepository.save(u);
        return profile(u);
    }

    /** Responders: on duty (help requests come to you first, nearest first) or off duty. */
    @Transactional
    public Map<String, Object> setDuty(User user, boolean onDuty) {
        User u = userRepository.findById(user.getId()).orElseThrow();
        if (u.getResponderTeam() == null) throw ApiException.badRequest("Only responder accounts go on duty.");
        u.setOnDuty(onDuty);
        u.setDutyChangedAt(LocalDateTime.now());
        userRepository.save(u);
        return profile(u);
    }

    @Transactional
    public Map<String, Object> withdrawRequest(User user) {
        User u = userRepository.findById(user.getId()).orElseThrow();
        u.setRequestedTeam(null);
        u.setRequestedUnit(null);
        u.setRequestNote(null);
        u.setRequestStatus(null);
        u.setRequestedAt(null);
        userRepository.save(u);
        return profile(u);
    }

    private Map<String, Object> row(EmergencyContact c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("phone", c.getPhone());
        m.put("relation", c.getRelation());
        m.put("position", c.getPosition());
        m.put("usesRaksio", !userRepository.findByPhone(c.getPhone()).isEmpty());
        return m;
    }
}
