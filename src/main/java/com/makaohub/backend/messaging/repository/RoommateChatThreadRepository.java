package com.makaohub.backend.messaging.repository;

import com.makaohub.backend.messaging.domain.RoommateChatThread;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoommateChatThreadRepository
        extends JpaRepository<RoommateChatThread, UUID> {

    Optional<RoommateChatThread>
    findByRoommateInterestId(UUID roommateInterestId);

    Optional<RoommateChatThread>
    findByIdAndRenterId(
            UUID threadId,
            UUID renterId
    );

    Optional<RoommateChatThread>
    findByIdAndSublessorId(
            UUID threadId,
            UUID sublessorId
    );

    Page<RoommateChatThread>
    findAllByRenterIdOrderByCreatedAtDesc(
            UUID renterId,
            Pageable pageable
    );

    Page<RoommateChatThread>
    findAllBySublessorIdOrderByCreatedAtDesc(
            UUID sublessorId,
            Pageable pageable
    );
}
