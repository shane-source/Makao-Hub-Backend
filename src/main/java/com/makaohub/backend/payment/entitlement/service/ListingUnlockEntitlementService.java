package com.makaohub.backend.payment.entitlement.service;

import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.entitlement.domain.ListingUnlockEntitlement;
import com.makaohub.backend.payment.entitlement.domain.ListingUnlockStatus;
import com.makaohub.backend.payment.entitlement.repository.ListingUnlockEntitlementRepository;
import com.makaohub.backend.payment.service.LandlordOfferService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Converts successful mock listing-unlock payments into server-side access rights. */
@Service
public class ListingUnlockEntitlementService {

    private final ListingUnlockEntitlementRepository repository;
    private final LandlordOfferService landlordOffers;

    public ListingUnlockEntitlementService(ListingUnlockEntitlementRepository repository,
            LandlordOfferService landlordOffers) {
        this.repository = repository;
        this.landlordOffers = landlordOffers;
    }

    @Transactional
    public void activateForSuccessfulPayment(PaymentIntent intent) {
        landlordOffers.activate(intent);
        if (intent.getStatus() != PaymentStatus.SUCCEEDED
                || !isListingUnlock(intent.getPaymentType())
                || intent.getListingId() == null) {
            return;
        }
        if (repository.findByPaymentIntentId(intent.getId()).isPresent()) {
            return;
        }
        repository.save(new ListingUnlockEntitlement(
                intent.getId(), intent.getPayerUserId(), intent.getListingId(),
                intent.getPaymentType(), intent.getAmountMinor(), intent.getCurrency()
        ));
    }

    @Transactional(readOnly = true)
    public boolean hasActiveUnlock(UUID payerUserId, UUID listingId,
                                   PaymentType unlockType) {
        return repository.existsByPayerUserIdAndListingIdAndUnlockTypeAndStatus(
                payerUserId, listingId, unlockType, ListingUnlockStatus.ACTIVE
        );
    }

    private static boolean isListingUnlock(PaymentType type) {
        return type == PaymentType.EXACT_LOCATION_UNLOCK
                || type == PaymentType.VIDEO_UNLOCK
                || type == PaymentType.PHONE_UNLOCK;
    }
}
