package com.makaohub.backend.messaging.repository;

import com.makaohub.backend.messaging.domain.RentalChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RentalChatMessageRepository
        extends JpaRepository<RentalChatMessage, UUID> {

    /**
     * Finds a previously saved message when the client retries.
     *
     * The service must also confirm that the stored message belongs
     * to the requested, authorized thread.
     */
    Optional<RentalChatMessage> findBySenderIdAndClientMessageId(
            UUID senderId,
            UUID clientMessageId
    );

    /**
     * Returns the newest messages in an authorized conversation.
     *
     * The service supplies PageRequest.of(0, validatedLimit).
     */
    Slice<RentalChatMessage>
    findAllByChatThreadIdOrderByCreatedAtDescIdDesc(
            UUID chatThreadId,
            Pageable pageable
    );

    /**
     * Returns messages preceding the supplied timestamp and UUID.
     *
     * PostgreSQL compares the pair in the same order as the history
     * index: timestamp first, then UUID to break timestamp ties.
     */
    @Query(
            value = """
                    SELECT message.*
                    FROM rental_chat_messages message
                    WHERE message.chat_thread_id = :chatThreadId
                      AND (message.created_at, message.id)
                          < (:cursorCreatedAt, :cursorId)
                    ORDER BY message.created_at DESC, message.id DESC
                    """,
            nativeQuery = true
    )
    Slice<RentalChatMessage> findBeforeCursor(
            @Param("chatThreadId") UUID chatThreadId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );
}