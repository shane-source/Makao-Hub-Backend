package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.api.CreateListingDraftRequest;
import com.makaohub.backend.property.api.ListingResponse;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.repository.ListingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class LandlordListingService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ListingRepository listingRepository;

    public LandlordListingService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            ListingRepository listingRepository
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
    }

    @Transactional
    public ListingResponse createDraft(
            UUID authenticatedUserId,
            CreateListingDraftRequest request
    ) {
        requireActiveLandlord(authenticatedUserId);

        Listing listing = new Listing(
                authenticatedUserId,
                request.title(),
                request.description(),
                request.propertyType(),
                request.monthlyRentKes(),
                request.bedroomCount(),
                request.bathroomCount(),
                request.furnished(),
                request.availableFrom()
        );

        return toResponse(listingRepository.save(listing));
    }

    @Transactional(readOnly = true)
    public List<ListingResponse> getMyListings(
            UUID authenticatedUserId
    ) {
        requireActiveLandlord(authenticatedUserId);

        return listingRepository
                .findAllByLandlordIdOrderByUpdatedAtDesc(authenticatedUserId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ListingResponse updateDraft(
            UUID authenticatedUserId,
            UUID listingId,
            CreateListingDraftRequest request
    ) {
        requireActiveLandlord(authenticatedUserId);

        Listing listing = listingRepository.findByIdAndLandlordId(
                listingId,
                authenticatedUserId
        ).orElseThrow(LandlordListingService::listingNotFound);

        if (listing.getStatus() != ListingStatus.DRAFT) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only draft listings can be edited."
            );
        }

        listing.updateDetails(
                request.title(),
                request.description(),
                request.propertyType(),
                request.monthlyRentKes(),
                request.bedroomCount(),
                request.bathroomCount(),
                request.furnished(),
                request.availableFrom()
        );

        return toResponse(listing);
    }

    private void requireActiveLandlord(UUID authenticatedUserId) {
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(LandlordListingService::accountUnavailable);

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw accountUnavailable();
        }

        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                authenticatedUserId,
                RoleName.LANDLORD
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only landlords can manage listings."
            );
        }
    }

    private ListingResponse toResponse(Listing listing) {
        return new ListingResponse(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getPropertyType(),
                listing.getMonthlyRentKes(),
                listing.getBedroomCount(),
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
                "Listing not found."
        );
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}