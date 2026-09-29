package com.droppoint.service;

import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles user authentication and multi-role user management (ADMIN, OWNER, MANAGER, AGENT).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public record UserView(Long id, String name, String phone, String role, Long branchId, String pin) { }

    public record LoginRequest(String phone, String pin) { }

    public record CreateUserRequest(String name, String phone, String role, String pin) { }

    private final JdbcTemplate jdbc;

    public AuthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public UserView login(String phone, String pin) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("Phone number is required");
        }
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("PIN is required");
        }
        List<UserView> users = jdbc.query("""
                SELECT id, full_name, phone, role, branch_id, pin
                FROM app_user
                WHERE phone = ? AND active = TRUE
                """,
                (rs, rowNum) -> new UserView(
                        rs.getLong("id"),
                        rs.getString("full_name"),
                        rs.getString("phone"),
                        rs.getString("role"),
                        rs.getObject("branch_id") == null ? null : rs.getLong("branch_id"),
                        rs.getString("pin")
                ),
                phone.trim());

        if (users.isEmpty()) {
            throw new NoSuchElementException("User not found with phone " + phone);
        }

        UserView user = users.get(0);
        if (!pin.trim().equals(user.pin())) {
            throw new IllegalArgumentException("Invalid PIN");
        }

        log.info("User {} logged in successfully as {}", user.phone(), user.role());
        return user;
    }

    @Transactional(readOnly = true)
    public List<UserView> listAllUsers() {
        return jdbc.query("""
                SELECT id, full_name, phone, role, branch_id, pin
                FROM app_user
                WHERE active = TRUE
                ORDER BY role, full_name
                """,
                (rs, rowNum) -> new UserView(
                        rs.getLong("id"),
                        rs.getString("full_name"),
                        rs.getString("phone"),
                        rs.getString("role"),
                        rs.getObject("branch_id") == null ? null : rs.getLong("branch_id"),
                        rs.getString("pin")
                ));
    }

    @Transactional
    public UserView createUser(String name, String phone, String role, String pin) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("Phone is required");
        }
        String userRole = (role != null) ? role.toUpperCase().trim() : "AGENT";
        if (!List.of("ADMIN", "OWNER", "MANAGER", "AGENT").contains(userRole)) {
            throw new IllegalArgumentException("Role must be one of ADMIN, OWNER, MANAGER, AGENT");
        }
        String userPin = (pin != null && !pin.isBlank()) ? pin.trim() : "1234";

        Long id = jdbc.queryForObject("""
                INSERT INTO app_user (full_name, phone, role, branch_id, active, pin, created_at)
                VALUES (?, ?, ?, 1, TRUE, ?, now())
                RETURNING id
                """, Long.class, name.trim(), phone.trim(), userRole, userPin);

        log.info("Created user {}: {} ({}) role {}", id, name, phone, userRole);
        return new UserView(id, name.trim(), phone.trim(), userRole, 1L, userPin);
    }

    @Transactional
    public void deleteUser(Long id) {
        jdbc.update("UPDATE app_user SET active = FALSE WHERE id = ?", id);
        log.info("Deactivated user {}", id);
    }
}
