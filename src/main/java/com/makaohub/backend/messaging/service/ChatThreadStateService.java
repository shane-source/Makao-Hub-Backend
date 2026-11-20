package com.makaohub.backend.messaging.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Lets each participant archive or delete a conversation for themselves only.
 * Nothing is removed from the conversation itself, so the other participant, reports and
 * moderation keep working exactly as before.
 */
@Service
public class ChatThreadStateService {

    public record ThreadState(
            String kind,
            UUID threadId,
            String state,
            OffsetDateTime changedAt,
            OffsetDateTime clearedAt
    ) {
    }

    private final JdbcTemplate jdbc;

    public ChatThreadStateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ThreadState> list(UUID userId) {
        return jdbc.query(
                """
                SELECT kind, thread_id, state, updated_at, cleared_at
                FROM chat_thread_user_states
                WHERE user_id = ?
                """,
                (rs, row) -> new ThreadState(
                        rs.getString("kind"),
                        rs.getObject("thread_id", UUID.class),
                        rs.getString("state"),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getObject("cleared_at", OffsetDateTime.class)
                ),
                userId
        );
    }

    /**
     * ARCHIVED and DELETED hide the conversation for this person. ACTIVE brings an archived
     * conversation back to the inbox. A deleted conversation cannot be restored; it only
     * returns by itself when the other person sends a new message.
     */
    @Transactional
    public void change(UUID userId, String kind, UUID threadId, String state) {
        String normalizedKind = kind == null ? "" : kind.toUpperCase();
        String normalizedState = state == null ? "" : state.toUpperCase();

        if (!isParticipant(userId, normalizedKind, threadId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation unavailable.");
        }

        Timestamp now = Timestamp.from(Instant.now());

        switch (normalizedState) {
            case "ARCHIVED" -> jdbc.update(
                    """
                    INSERT INTO chat_thread_user_states (kind, thread_id, user_id, state, cleared_at, updated_at)
                    VALUES (?, ?, ?, 'ARCHIVED', NULL, ?)
                    ON CONFLICT (kind, thread_id, user_id)
                    DO UPDATE SET state = 'ARCHIVED', updated_at = EXCLUDED.updated_at
                    """,
                    normalizedKind, threadId, userId, now
            );
            case "DELETED" -> jdbc.update(
                    """
                    INSERT INTO chat_thread_user_states (kind, thread_id, user_id, state, cleared_at, updated_at)
                    VALUES (?, ?, ?, 'DELETED', ?, ?)
                    ON CONFLICT (kind, thread_id, user_id)
                    DO UPDATE SET state = 'DELETED', cleared_at = EXCLUDED.cleared_at, updated_at = EXCLUDED.updated_at
                    """,
                    normalizedKind, threadId, userId, now, now
            );
            case "ACTIVE" -> jdbc.update(
                    """
                    DELETE FROM chat_thread_user_states
                    WHERE kind = ? AND thread_id = ? AND user_id = ? AND state = 'ARCHIVED'
                    """,
                    normalizedKind, threadId, userId
            );
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "State must be ARCHIVED, DELETED or ACTIVE."
            );
        }
    }

    private boolean isParticipant(UUID userId, String kind, UUID threadId) {
        String sql = switch (kind) {
            case "RENTAL" -> """
                    SELECT COUNT(*) FROM rental_chat_threads
                    WHERE id = ? AND (renter_id = ? OR landlord_id = ?)
                    """;
            case "ROOMMATE" -> """
                    SELECT COUNT(*) FROM roommate_chat_threads
                    WHERE id = ? AND (renter_id = ? OR sublessor_id = ?)
                    """;
            default -> null;
        };

        if (sql == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown conversation type.");
        }

        Integer count = jdbc.queryForObject(sql, Integer.class, threadId, userId, userId);
        return count != null && count > 0;
    }
}
