package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.api.CreateSublessorListingRequest;
import com.makaohub.backend.property.api.SublessorListingResponse;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.ListingMediaType;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.repository.SublessorListingLocationRepository;
import com.makaohub.backend.property.repository.SublessorListingMediaRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.verification.domain.RoleVerification;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class SublessorListingService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final SublessorListingRepository listingRepository;
    private final SublessorListingLocationRepository locationRepository;
    private final SublessorListingMediaRepository mediaRepository;
    private final RoleVerificationRepository verificationRepository;

    public SublessorListingService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            SublessorListingRepository listingRepository,
            SublessorListingLocationRepository locationRepository,
            SublessorListingMediaRepository mediaRepository,
            RoleVerificationRepository verificationRepository
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.locationRepository = locationRepository;
        this.mediaRepository = mediaRepository;
        this.verificationRepository = verificationRepository;
    }

    @Transactional
    public SublessorListingResponse createDraft(
            UUID authenticatedUserId,
            CreateSublessorListingRequest request
    ) {
        requireActiveSublessor(authenticatedUserId);

        SublessorListing listing = new SublessorListing(
                authenticatedUserId,
                request.title(),
                request.description(),
                request.spaceType(),
                request.monthlyRentKes(),
                request.bathroomCount(),
                request.furnished(),
                request.availableFrom()
        );

        return toResponse(
                listingRepository.save(listing)
        );
    }

    @Transactional(readOnly = true)
    public List<SublessorListingResponse> getMyListings(
            UUID authenticatedUserId
    ) {
        requireActiveSublessor(authenticatedUserId);

        return listingRepository
                .findAllBySublessorIdOrderByCreatedAtDesc(
                        authenticatedUserId
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public SublessorListingResponse updateDraft(
            UUID authenticatedUserId,
            UUID listingId,
            CreateSublessorListingRequest request
    ) {
        requireActiveSublessor(authenticatedUserId);

        SublessorListing listing =
                requireOwnedListing(
                        authenticatedUserId,
                        listingId
                );

        if (listing.getStatus() != ListingStatus.DRAFT) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only draft listings can be edited."
            );
        }

        listing.updateDetails(
                request.title(),
                request.description(),
                request.spaceType(),
                request.monthlyRentKes(),
                request.bathroomCount(),
                request.furnished(),
                request.availableFrom()
        );

        return toResponse(listing);
    }

    @Transactional
    public SublessorListingResponse publish(
            UUID authenticatedUserId,
            UUID listingId
    ) {
        requireActiveSublessor(authenticatedUserId);

        SublessorListing listing =
                requireOwnedListing(
                        authenticatedUserId,
                        listingId
                );

        requireVerifiedSublessor(authenticatedUserId);
        requireLocation(listingId);
        requireConfirmedMedia(listingId);

        listing.publish();

        return toResponse(
                listingRepository.save(listing)
        );
    }

    @Transactional
    public SublessorListingResponse pause(
            UUID authenticatedUserId,
            UUID listingId
    ) {
        requireActiveSublessor(authenticatedUserId);

        SublessorListing listing =
                requireOwnedListing(
                        authenticatedUserId,
                        listingId
                );

        listing.pause();

        return toResponse(
                listingRepository.save(listing)
        );
    }

    @Transactional
    public SublessorListingResponse resume(
            UUID authenticatedUserId,
            UUID listingId
    ) {
        requireActiveSublessor(authenticatedUserId);

        SublessorListing listing =
                requireOwnedListing(
                        authenticatedUserId,
                        listingId
                );

        requireVerifiedSublessor(authenticatedUserId);

        listing.resume();

        return toResponse(
                listingRepository.save(listing)
        );
    }

    @Transactional
    public SublessorListingResponse archive(
            UUID authenticatedUserId,
            UUID listingId
    ) {
        requireActiveSublessor(authenticatedUserId);

        SublessorListing listing =
                requireOwnedListing(
                        authenticatedUserId,
                        listingId
                );

        listing.archive();

        return toResponse(
                listingRepository.save(listing)
        );
    }

    private SublessorListing requireOwnedListing(
            UUID authenticatedUserId,
            UUID listingId
    ) {
        return listingRepository.findByIdAndSublessorId(
                listingId,
                authenticatedUserId
        ).orElseThrow(
                SublessorListingService::listingNotFound
        );
    }

    private void requireVerifiedSublessor(
            UUID authenticatedUserId
    ) {
        RoleVerification verification =
                verificationRepository.findByUserIdAndRole(
                        authenticatedUserId,
                        RoleName.SUBLESSOR
                ).orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Sublessor verification must be approved before publishing."
                ));

        if (verification.getStatus()
                != VerificationStatus.VERIFIED) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Sublessor verification must be approved before publishing."
            );
        }
    }

    private void requireLocation(UUID listingId) {
        locationRepository
                .findBySublessorListingId(listingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Set the room location before publishing."
                ));
    }

    private void requireConfirmedMedia(UUID listingId) {
        if (!mediaRepository
                .existsBySublessorListingIdAndMediaTypeAndConfirmedAtIsNotNull(
                        listingId, ListingMediaType.IMAGE
                )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Upload and confirm at least one image before publishing."
            );
        }
    }

    private void requireActiveSublessor(
            UUID authenticatedUserId
    ) {
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(
                        SublessorListingService::accountUnavailable
                );

        if (user.getAccountStatus()
                != AccountStatus.ACTIVE) {
            throw accountUnavailable();
        }

        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                authenticatedUserId,
                RoleName.SUBLESSOR
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only sublessors can manage sublessor listings."
            );
        }
    }

    private SublessorListingResponse toResponse(
            SublessorListing listing
    ) {
        return new SublessorListingResponse(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getSpaceType(),
                listing.getMonthlyRentKes(),
                listing.getBathroomCount(),
                listing.isFurnished(),
                listing.getAvailableFrom(),
                listing.getStatus(),
                listing.getCreatedAt(),
                listing.getUpdatedAt()
        );
    }

    private static ResponseStatusException listingNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Sublessor listing not found."
        );
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}
