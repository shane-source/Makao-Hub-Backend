package com.makaohub.backend.profile.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Records account-owner requests without changing account or financial state. */
@Service
public class AccountDeletionRequestService {
    private final JdbcTemplate sql;

    public AccountDeletionRequestService(JdbcTemplate sql) { this.sql = sql; }

    public record Request(UUID id, String status, OffsetDateTime requestedAt) { }

    @Transactional
    public Request request(UUID userId) {
        requireActiveCustomer(userId);
        // The partial unique index prevents concurrent taps from creating two open cases.
        sql.update("""
            INSERT INTO account_deletion_requests(user_id)
            VALUES (?)
            ON CONFLICT (user_id) WHERE status IN ('REQUESTED', 'IN_REVIEW') DO NOTHING
            """, userId);
        return current(userId).getFirst();
    }

    @Transactional(readOnly = true)
    public List<Request> mine(UUID userId) {
        requireActiveCustomer(userId);
        return current(userId);
    }

    @Transactional(readOnly = true)
    public List<Request> pendingForStaff(int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return sql.query("""
            SELECT id, status, requested_at FROM account_deletion_requests
            WHERE status = 'REQUESTED' ORDER BY requested_at, id LIMIT ?
            """, (rs, row) -> new Request(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getObject("requested_at", OffsetDateTime.class)), limit);
    }

    private List<Request> current(UUID userId) {
        return sql.query("""
            SELECT id, status, requested_at FROM account_deletion_requests
            WHERE user_id = ? AND status IN ('REQUESTED', 'IN_REVIEW')
            ORDER BY requested_at DESC LIMIT 1
            """, (rs, row) -> new Request(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getObject("requested_at", OffsetDateTime.class)), userId);
    }

    private void requireActiveCustomer(UUID userId) {
        if (userId == null || sql.queryForObject("""
            SELECT COUNT(*) FROM users u JOIN user_roles ur ON ur.user_id = u.id
            JOIN roles r ON r.id = ur.role_id
            WHERE u.id = ? AND u.account_status = 'ACTIVE'
              AND r.name IN ('RENTER', 'LANDLORD', 'SUBLESSOR')
            """, Integer.class, userId) != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
