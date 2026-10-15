package com.makaohub.backend.messaging.repository;

import com.makaohub.backend.messaging.domain.RoommateChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RoommateChatMessageRepository
        extends JpaRepository<RoommateChatMessage, UUID> {

    /**
     * Finds a previously stored message using the sender and the
     * client-generated message ID.
     *
     * This supports idempotency. If Android or the website retries a
     * message after losing the response, the service returns the
     * existing message instead of inserting a duplicate.
     */
    Optional<RoommateChatMessage>
    findBySenderIdAndClientMessageId(
            UUID senderId,
            UUID clientMessageId
    );

    /**
     * Retrieves the newest message page for a thread.
     *
     * Slice is used instead of Page because chat history does not need
     * a potentially expensive COUNT(*) query. The client only needs to
     * know whether an older page exists.
     */
    Slice<RoommateChatMessage>
    findAllByChatThreadIdOrderByCreatedAtDescIdDesc(
            UUID chatThreadId,
            Pageable pageable
    );

    /**
     * Retrieves messages older than a cursor.
     *
     * The cursor consists of:
     * - createdAt
     * - id
     *
     * ID is the deterministic tie-breaker when two messages have the
     * same creation timestamp.
     *
     * This is a native PostgreSQL query because it compares UUID values
     * as part of the stable cursor ordering.
     */
    @Query(
            value = """
                    SELECT message
                    FROM roommate_chat_messages message
                    WHERE message.chat_thread_id = :chatThreadId
                      AND (
                            message.created_at < :cursorCreatedAt
                            OR (
                                message.created_at = :cursorCreatedAt
                                AND message.id < :cursorId
                            )
                          )
                    ORDER BY
                        message.created_at DESC,
                        message.id DESC
                    """,
            nativeQuery = true
    )
    Slice<RoommateChatMessage> findBeforeCursor(
            @Param("chatThreadId")
            UUID chatThreadId,

            @Param("cursorCreatedAt")
            Instant cursorCreatedAt,

            @Param("cursorId")
            UUID cursorId,

            Pageable pageable
    );
}