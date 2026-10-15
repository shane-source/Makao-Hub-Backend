package com.makaohub.backend.messaging.repository;

import com.makaohub.backend.messaging.domain.ChatThreadStatus;
import com.makaohub.backend.messaging.domain.RentalChatThread;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RentalChatThreadRepository
        extends JpaRepository<RentalChatThread, UUID> {

    Optional<RentalChatThread> findByRentalApplicationId(
            UUID rentalApplicationId
    );

    Optional<RentalChatThread> findByIdAndRenterId(
            UUID threadId,
            UUID renterId
    );

    Optional<RentalChatThread> findByIdAndLandlordId(
            UUID threadId,
            UUID landlordId
    );

    Page<RentalChatThread> findAllByRenterIdOrderByCreatedAtDesc(
            UUID renterId,
            Pageable pageable
    );

    Page<RentalChatThread> findAllByLandlordIdOrderByCreatedAtDesc(
            UUID landlordId,
            Pageable pageable
    );

    Page<RentalChatThread>
    findAllByRenterIdAndStatusOrderByCreatedAtDescIdDesc(
            UUID renterId,
            ChatThreadStatus status,
            Pageable pageable
    );

    Page<RentalChatThread>
    findAllByLandlordIdAndStatusOrderByCreatedAtDescIdDesc(
            UUID landlordId,
            ChatThreadStatus status,
            Pageable pageable
    );
}
