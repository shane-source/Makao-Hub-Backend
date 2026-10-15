package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.auth.service.PhoneProtectionService;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.entitlement.service.ListingUnlockEntitlementService;
import com.makaohub.backend.property.api.ListingOwnerPhoneResponse;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Decrypts an owner's phone only after a renter's paid phone entitlement is verified. */
@Service
@Profile("mock")
public class RenterListingPhoneAccessService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ListingRepository landlordListingRepository;
    private final SublessorListingRepository sublessorListingRepository;
    private final ListingUnlockEntitlementService entitlementService;
    private final PhoneProtectionService phoneProtectionService;

    public RenterListingPhoneAccessService(UserRepository userRepository,
                                           UserRoleRepository userRoleRepository,
                                           ListingRepository landlordListingRepository,
                                           SublessorListingRepository sublessorListingRepository,
                                           ListingUnlockEntitlementService entitlementService,
                                           PhoneProtectionService phoneProtectionService) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.landlordListingRepository = landlordListingRepository;
        this.sublessorListingRepository = sublessorListingRepository;
        this.entitlementService = entitlementService;
        this.phoneProtectionService = phoneProtectionService;
    }

    @Transactional(readOnly = true)
    public ListingOwnerPhoneResponse getOwnerPhone(UUID renterId, UUID listingId) {
        requireActiveRenter(renterId);
        if (!entitlementService.hasActiveUnlock(
                renterId, listingId, PaymentType.PHONE_UNLOCK
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Pay for the phone unlock before requesting the owner's phone."
            );
        }

        Listing landlord = landlordListingRepository
                .findByIdAndStatus(listingId, ListingStatus.PUBLISHED).orElse(null);
        SublessorListing sublessor = sublessorListingRepository.findById(listingId)
                .filter(listing -> listing.getStatus() == ListingStatus.PUBLISHED)
                .orElse(null);
        if (landlord != null && sublessor != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The listing identifier is ambiguous.");
        }
        UUID ownerId = landlord != null
                ? landlord.getLandlordId()
                : sublessor == null ? null : sublessor.getSublessorId();
        if (ownerId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Published listing not found.");
        }

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Listing owner not found."
                ));
        if (owner.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.GONE, "Listing owner is unavailable.");
        }
        return new ListingOwnerPhoneResponse(
                owner.getFullName(),
                phoneProtectionService.decrypt(owner.getPhoneCiphertext())
        );
    }

    private void requireActiveRenter(UUID renterId) {
        User renter = userRepository.findById(renterId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Account is unavailable."
                ));
        if (renter.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not active.");
        }
        if (!userRoleRepository.existsByUser_IdAndRole_Name(renterId, RoleName.RENTER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only renters can unlock listing phones.");
        }
    }
}
