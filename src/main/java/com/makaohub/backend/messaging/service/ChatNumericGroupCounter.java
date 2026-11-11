package com.makaohub.backend.messaging.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Atomically tracks only a capped numeric-group count for each sender and chat. */
@Service
public class ChatNumericGroupCounter {
    private final JdbcTemplate jdbc;

    public ChatNumericGroupCounter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs in the message transaction; a failed send rolls its count back. */
    public boolean exceedsLimit(boolean rental, UUID threadId, UUID senderId, int groups) {
        if (groups <= 0) {
            return false;
        }
        String table = rental ? "rental_chat_numeric_counts" : "roommate_chat_numeric_counts";
        Integer total = jdbc.queryForObject(
                "INSERT INTO " + table + " (chat_thread_id, sender_id, group_count) VALUES (?, ?, ?) "
                        + "ON CONFLICT (chat_thread_id, sender_id) DO UPDATE "
                        + "SET group_count = LEAST(6, " + table + ".group_count + EXCLUDED.group_count) "
                        + "RETURNING group_count",
                Integer.class, threadId, senderId, Math.min(groups, 6));
        return total != null && total > 5;
    }
}
