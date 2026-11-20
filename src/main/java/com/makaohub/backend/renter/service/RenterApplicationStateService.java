package com.makaohub.backend.renter.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Lets a renter archive or delete an application from their own list. Only their view changes:
 * the application, the owner's list and every record of it stay exactly as they were.
 */
@Service
public class RenterApplicationStateService {

    public record ApplicationState(String kind, UUID applicationId, String state) {
    }

    private final JdbcTemplate jdbc;

    public RenterApplicationStateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ApplicationState> list(UUID userId) {
        return jdbc.query(
                "SELECT kind, application_id, state FROM renter_application_states WHERE user_id = ?",
                (rs, row) -> new ApplicationState(
                        rs.getString("kind"), rs.getObject("application_id", UUID.class), rs.getString("state")),
                userId);
    }

    /** ARCHIVED and DELETED hide the application. ACTIVE brings an archived one back. Deleted cannot be restored. */
    @Transactional
    public void change(UUID userId, String kind, UUID applicationId, String state) {
        String k = kind == null ? "" : kind.toUpperCase();
        String s = state == null ? "" : state.toUpperCase();

        String table = switch (k) {
            case "LANDLORD" -> "rental_applications";
            case "SUBLESSOR" -> "sublessor_rental_applications";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown application type.");
        };

        Integer owned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ? AND renter_id = ?",
                Integer.class, applicationId, userId);
        if (owned == null || owned == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Application unavailable.");
        }

        if (s.equals("ARCHIVED") || s.equals("DELETED")) {
            Integer live = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM bookings
                    WHERE source_application_id = ? AND renter_id = ?
                      AND status IN ('PAYMENT_PENDING', 'PAID_HELD') AND released_at IS NULL
                    """, Integer.class, applicationId, userId);
            if (live != null && live > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This application has a booking in progress. You can archive it after the booking finishes.");
            }
        }

        switch (s) {
            case "ARCHIVED", "DELETED" -> jdbc.update("""
                    INSERT INTO renter_application_states (kind, application_id, user_id, state, updated_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (kind, application_id, user_id)
                    DO UPDATE SET state = EXCLUDED.state, updated_at = CURRENT_TIMESTAMP
                    """, k, applicationId, userId, s);
            case "ACTIVE" -> jdbc.update("""
                    DELETE FROM renter_application_states
                    WHERE kind = ? AND application_id = ? AND user_id = ? AND state = 'ARCHIVED'
                    """, k, applicationId, userId);
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "State must be ARCHIVED, DELETED or ACTIVE.");
        }
    }
}
