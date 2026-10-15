package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.entitlement.service.ListingUnlockEntitlementService;
import com.makaohub.backend.property.api.ExactListingLocationResponse;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingLocation;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.domain.SublessorListingLocation;
import com.makaohub.backend.property.repository.ListingLocationRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.property.repository.SublessorListingLocationRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Enforces renter ownership of the paid exact-location entitlement per request. */
@Service
@Profile("mock")
public class RenterListingLocationAccessService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ListingRepository landlordListingRepository;
    private final SublessorListingRepository sublessorListingRepository;
    private final ListingLocationRepository landlordLocationRepository;
    private final SublessorListingLocationRepository sublessorLocationRepository;
    private final ListingUnlockEntitlementService entitlementService;
    private final ListingLocationProtectionService protectionService;

    public RenterListingLocationAccessService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            ListingRepository landlordListingRepository,
            SublessorListingRepository sublessorListingRepository,
            ListingLocationRepository landlordLocationRepository,
            SublessorListingLocationRepository sublessorLocationRepository,
            ListingUnlockEntitlementService entitlementService,
            ListingLocationProtectionService protectionService
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.landlordListingRepository = landlordListingRepository;
        this.sublessorListingRepository = sublessorListingRepository;
        this.landlordLocationRepository = landlordLocationRepository;
        this.sublessorLocationRepository = sublessorLocationRepository;
        this.entitlementService = entitlementService;
        this.protectionService = protectionService;
    }

    @Transactional(readOnly = true)
    public ExactListingLocationResponse getExactLocation(
            UUID renterId,
            UUID listingId
    ) {
        requireActiveRenter(renterId);
        if (!entitlementService.hasActiveUnlock(
                renterId, listingId, PaymentType.EXACT_LOCATION_UNLOCK
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Pay for the exact-location unlock before requesting this location."
            );
        }

        Listing landlordListing = landlordListingRepository
                .findByIdAndStatus(listingId, ListingStatus.PUBLISHED)
                .orElse(null);
        SublessorListing sublessorListing = sublessorListingRepository
                .findById(listingId)
                .filter(listing -> listing.getStatus() == ListingStatus.PUBLISHED)
                .orElse(null);

        if (landlordListing != null && sublessorListing != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The listing identifier is ambiguous."
            );
        }
        if (landlordListing != null) {
            ListingLocation location = landlordLocationRepository.findById(listingId)
                    .orElseThrow(RenterListingLocationAccessService::locationNotFound);
            var coordinates = protectionService.decryptCoordinates(
                    location.getExactCoordinatesCiphertext()
            );
            return new ExactListingLocationResponse(
                    location.getAreaLabel(), coordinates.latitude(), coordinates.longitude()
            );
        }
        if (sublessorListing != null) {
            SublessorListingLocation location = sublessorLocationRepository
                    .findBySublessorListingId(listingId)
                    .orElseThrow(RenterListingLocationAccessService::locationNotFound);
            var coordinates = protectionService.decryptCoordinates(
                    location.getExactCoordinatesCiphertext()
            );
            return new ExactListingLocationResponse(
                    location.getAreaLabel(), coordinates.latitude(), coordinates.longitude()
            );
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Published listing not found.");
    }

    private void requireActiveRenter(UUID renterId) {
        User user = userRepository.findById(renterId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Account is unavailable."
                ));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not active.");
        }
        if (!userRoleRepository.existsByUser_IdAndRole_Name(renterId, RoleName.RENTER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only renters can unlock locations.");
        }
    }

    private static ResponseStatusException locationNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing location not found.");
    }
}
