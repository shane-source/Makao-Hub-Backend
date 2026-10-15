package com.makaohub.backend.messaging.repository;

import com.makaohub.backend.messaging.domain.ChatUnlockEntitlement;
import com.makaohub.backend.messaging.domain.ChatUnlockStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatUnlockEntitlementRepository
        extends JpaRepository<ChatUnlockEntitlement, UUID> {

    Optional<ChatUnlockEntitlement>
    findByPayerUserIdAndIdempotencyKey(
            UUID payerUserId,
            String idempotencyKey
    );

    Optional<ChatUnlockEntitlement>
    findByProviderPaymentReference(
            String providerPaymentReference
    );

    Optional<ChatUnlockEntitlement>
    findFirstByChatThreadIdAndStatusOrderByCreatedAtDesc(
            UUID chatThreadId,
            ChatUnlockStatus status
    );

    boolean existsByChatThreadIdAndStatus(
            UUID chatThreadId,
            ChatUnlockStatus status
    );

    List<ChatUnlockEntitlement>
    findAllByChatThreadIdOrderByCreatedAtDesc(
            UUID chatThreadId
    );
}