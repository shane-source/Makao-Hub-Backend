package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.api.SetListingLocationRequest;
import com.makaohub.backend.property.api.SublessorListingLocationResponse;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.domain.SublessorListingLocation;
import com.makaohub.backend.property.repository.SublessorListingLocationRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.UUID;

@Service
public class SublessorListingLocationService {

    private static final short APPROXIMATE_RADIUS_METERS = 500;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final SublessorListingRepository listingRepository;
    private final SublessorListingLocationRepository locationRepository;
    private final ListingLocationProtectionService locationProtectionService;

    private final SecureRandom secureRandom = new SecureRandom();

    public SublessorListingLocationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            SublessorListingRepository listingRepository,
            SublessorListingLocationRepository locationRepository,
            ListingLocationProtectionService locationProtectionService
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.locationRepository = locationRepository;
        this.locationProtectionService = locationProtectionService;
    }

    @Transactional
    public SublessorListingLocationResponse setLocation(
            UUID authenticatedUserId,
            UUID listingId,
            SetListingLocationRequest request
    ) {
        SublessorListing listing = requireOwnedListing(
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

        SublessorListingLocation location =
                locationRepository
                        .findBySublessorListingId(listingId)
                        .orElseGet(() -> new SublessorListingLocation(
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

        /*
         * This response is returned to the owner only.
         * Public listing responses must use the stored approximate values.
         */
        return new SublessorListingLocationResponse(
                request.areaLabel().strip(),
                request.latitude(),
                request.longitude()
        );
    }

    @Transactional(readOnly = true)
    public SublessorListingLocationResponse getLocation(
            UUID authenticatedUserId,
            UUID listingId
    ) {
        requireOwnedListing(authenticatedUserId, listingId);

        SublessorListingLocation location =
                locationRepository
                        .findBySublessorListingId(listingId)
                        .orElseThrow(
                                SublessorListingLocationService
                                        ::locationNotFound
                        );

        ListingLocationProtectionService.ExactCoordinates
                exactCoordinates =
                locationProtectionService.decryptCoordinates(
                        location.getExactCoordinatesCiphertext()
                );

        return new SublessorListingLocationResponse(
                location.getAreaLabel(),
                exactCoordinates.latitude(),
                exactCoordinates.longitude()
        );
    }

    private SublessorListing requireOwnedListing(
            UUID authenticatedUserId,
            UUID listingId
    ) {
        requireActiveSublessor(authenticatedUserId);

        return listingRepository.findByIdAndSublessorId(
                listingId,
                authenticatedUserId
        ).orElseThrow(
                SublessorListingLocationService::listingNotFound
        );
    }

    private void requireActiveSublessor(
            UUID authenticatedUserId
    ) {
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(
                        SublessorListingLocationService
                                ::accountUnavailable
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
                    "Only sublessors can manage room locations."
            );
        }
    }

    private FuzzyPoint createFuzzyPoint(
            BigDecimal exactLatitude,
            BigDecimal exactLongitude
    ) {
        double bearing =
                secureRandom.nextDouble() * 2 * Math.PI;

        double distance =
                350 + secureRandom.nextDouble() * 150;

        double latitude = exactLatitude.doubleValue();
        double longitude = exactLongitude.doubleValue();

        double fuzzyLatitude =
                latitude
                        + distance * Math.cos(bearing) / 111_320d;

        double fuzzyLongitude =
                longitude
                        + distance * Math.sin(bearing)
                        / (
                        111_320d
                                * Math.cos(
                                Math.toRadians(latitude)
                        )
                );

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
                "Sublessor listing not found."
        );
    }

    private static ResponseStatusException locationNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Sublessor listing location not found."
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