package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.entitlement.service.ListingUnlockEntitlementService;
import com.makaohub.backend.property.api.PublicListingMediaResponse;
import com.makaohub.backend.property.domain.ListingMediaType;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.repository.ListingMediaRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.property.repository.SublessorListingMediaRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Returns video URLs only to a renter with an active listing-specific unlock. */
@Service
@Profile("mock")
public class RenterListingVideoAccessService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ListingRepository landlordListingRepository;
    private final SublessorListingRepository sublessorListingRepository;
    private final ListingMediaRepository landlordMediaRepository;
    private final SublessorListingMediaRepository sublessorMediaRepository;
    private final ListingUnlockEntitlementService entitlementService;
    private final SupabaseStorageClient storageClient;

    public RenterListingVideoAccessService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            ListingRepository landlordListingRepository,
            SublessorListingRepository sublessorListingRepository,
            ListingMediaRepository landlordMediaRepository,
            SublessorListingMediaRepository sublessorMediaRepository,
            ListingUnlockEntitlementService entitlementService,
            SupabaseStorageClient storageClient
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.landlordListingRepository = landlordListingRepository;
        this.sublessorListingRepository = sublessorListingRepository;
        this.landlordMediaRepository = landlordMediaRepository;
        this.sublessorMediaRepository = sublessorMediaRepository;
        this.entitlementService = entitlementService;
        this.storageClient = storageClient;
    }

    @Transactional(readOnly = true)
    public List<PublicListingMediaResponse> getVideos(UUID renterId, UUID listingId) {
        requireActiveRenter(renterId);
        if (!entitlementService.hasActiveUnlock(
                renterId, listingId, PaymentType.VIDEO_UNLOCK
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Pay for the video unlock before requesting listing videos."
            );
        }

        boolean landlord = landlordListingRepository
                .findByIdAndStatus(listingId, ListingStatus.PUBLISHED)
                .isPresent();
        boolean sublessor = sublessorListingRepository.findById(listingId)
                .map(listing -> listing.getStatus() == ListingStatus.PUBLISHED)
                .orElse(false);
        if (landlord == sublessor) {
            throw new ResponseStatusException(
                    landlord ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND,
                    landlord ? "The listing identifier is ambiguous." : "Published listing not found."
            );
        }

        if (landlord) {
            return landlordMediaRepository
                    .findAllByListingIdAndConfirmedAtIsNotNullOrderBySortOrderAsc(listingId)
                    .stream()
                    .filter(media -> media.getMediaType() == ListingMediaType.VIDEO)
                    .map(media -> new PublicListingMediaResponse(
                            media.getId(), media.getMediaType(), media.getContentType(),
                            media.getSortOrder(),
                            storageClient.createSignedListingMediaReadUrl(media.getObjectPath())
                    ))
                    .toList();
        }

        return sublessorMediaRepository
                .findAllBySublessorListingIdAndConfirmedAtIsNotNullOrderBySortOrderAsc(listingId)
                .stream()
                .filter(media -> media.getMediaType() == ListingMediaType.VIDEO)
                .map(media -> new PublicListingMediaResponse(
                        media.getId(), media.getMediaType(), media.getContentType(),
                        media.getSortOrder(),
                        storageClient.createSignedListingMediaReadUrl(media.getObjectPath())
                ))
                .toList();
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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only renters can unlock listing videos.");
        }
    }
}
