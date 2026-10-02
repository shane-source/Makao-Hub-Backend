package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.api.LandlordListingLocationResponse;
import com.makaohub.backend.property.api.SetListingLocationRequest;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingLocation;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.repository.ListingLocationRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.UUID;

@Service
public class LandlordListingLocationService {

    private static final short APPROXIMATE_RADIUS_METERS = 500;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ListingRepository listingRepository;
    private final ListingLocationRepository locationRepository;
    private final ListingLocationProtectionService locationProtectionService;
    private final SecureRandom secureRandom = new SecureRandom();

    public LandlordListingLocationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            ListingRepository listingRepository,
            ListingLocationRepository locationRepository,
            ListingLocationProtectionService locationProtectionService
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.locationRepository = locationRepository;
        this.locationProtectionService = locationProtectionService;
    }

    @Transactional
    public LandlordListingLocationResponse setLocation(
            UUID authenticatedUserId,
            UUID listingId,
            SetListingLocationRequest request
    ) {
        Listing listing = requireOwnedListing(
                authenticatedUserId,
                listingId
        );

        if (listing.getStatus() != ListingStatus.DRAFT) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only draft listings can change location."
            );
        }

        byte[] exactCoordinates =
                locationProtectionService.encryptCoordinates(
                        request.latitude(),
                        request.longitude()
                );

        FuzzyPoint fuzzyPoint = createFuzzyPoint(
                request.latitude(),
                request.longitude()
        );

        ListingLocation location = locationRepository.findById(listingId)
                .orElseGet(() -> new ListingLocation(
                        listingId,
                        request.areaLabel(),
                        exactCoordinates,
                        fuzzyPoint.latitude(),
                        fuzzyPoint.longitude(),
                        APPROXIMATE_RADIUS_METERS
                ));

        location.update(
                request.areaLabel(),
                exactCoordinates,
                fuzzyPoint.latitude(),
                fuzzyPoint.longitude(),
                APPROXIMATE_RADIUS_METERS
        );

        locationRepository.save(location);

        return new LandlordListingLocationResponse(
                request.areaLabel().strip(),
                request.latitude(),
                request.longitude()
        );
    }

    @Transactional(readOnly = true)
    public LandlordListingLocationResponse getLocation(
            UUID authenticatedUserId,
            UUID listingId
    ) {
        requireOwnedListing(authenticatedUserId, listingId);

        ListingLocation location = locationRepository.findById(listingId)
                .orElseThrow(LandlordListingLocationService::locationNotFound);

        ListingLocationProtectionService.ExactCoordinates exactCoordinates =
                locationProtectionService.decryptCoordinates(
                        location.getExactCoordinatesCiphertext()
                );

        return new LandlordListingLocationResponse(
                location.getAreaLabel(),
                exactCoordinates.latitude(),
                exactCoordinates.longitude()
        );
    }

    private Listing requireOwnedListing(
            UUID authenticatedUserId,
            UUID listingId
    ) {
        requireActiveLandlord(authenticatedUserId);

        return listingRepository.findByIdAndLandlordId(
                listingId,
                authenticatedUserId
        ).orElseThrow(LandlordListingLocationService::listingNotFound);
    }

    private void requireActiveLandlord(UUID authenticatedUserId) {
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(LandlordListingLocationService::accountUnavailable);

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw accountUnavailable();
        }

        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                authenticatedUserId,
                RoleName.LANDLORD
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only landlords can manage listing locations."
            );
        }
    }

    private FuzzyPoint createFuzzyPoint(
            BigDecimal exactLatitude,
            BigDecimal exactLongitude
    ) {
        double bearing = secureRandom.nextDouble() * 2 * Math.PI;
        double distance = 350 + secureRandom.nextDouble() * 150;

        double latitude = exactLatitude.doubleValue();
        double longitude = exactLongitude.doubleValue();

        double fuzzyLatitude = latitude
                + distance * Math.cos(bearing) / 111_320d;

        double fuzzyLongitude = longitude
                + distance * Math.sin(bearing)
                / (111_320d * Math.cos(Math.toRadians(latitude)));

        return new FuzzyPoint(
                BigDecimal.valueOf(fuzzyLatitude)
                        .setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(fuzzyLongitude)
                        .setScale(6, RoundingMode.HALF_UP)
        );
    }

    private static ResponseStatusException listingNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Listing not found."
        );
    }

    private static ResponseStatusException locationNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Listing location not found."
        );
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }

    private record FuzzyPoint(
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }
}