package com.makaohub.backend.payment.entitlement.repository;

import com.makaohub.backend.payment.entitlement.domain.ListingUnlockEntitlement;
import com.makaohub.backend.payment.entitlement.domain.ListingUnlockStatus;
import com.makaohub.backend.payment.domain.PaymentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ListingUnlockEntitlementRepository
        extends JpaRepository<ListingUnlockEntitlement, UUID> {

    Optional<ListingUnlockEntitlement> findByPaymentIntentId(UUID paymentIntentId);

    boolean existsByPayerUserIdAndListingIdAndUnlockTypeAndStatus(
            UUID payerUserId, UUID listingId, PaymentType unlockType,
            ListingUnlockStatus status
    );
}
