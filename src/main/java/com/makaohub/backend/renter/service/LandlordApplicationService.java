package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.renter.api.RentalApplicationResponse;
import com.makaohub.backend.renter.domain.RentalApplication;
import com.makaohub.backend.renter.repository.RentalApplicationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class LandlordApplicationService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ListingRepository listingRepository;
    private final RentalApplicationRepository applicationRepository;

    public LandlordApplicationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            ListingRepository listingRepository,
            RentalApplicationRepository applicationRepository
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.applicationRepository = applicationRepository;
    }

    @Transactional(readOnly = true)
    public List<RentalApplicationResponse> findForMyListings(
            UUID landlordId
    ) {
        requireActiveLandlord(landlordId);

        List<UUID> listingIds = listingRepository
                .findAllByLandlordIdOrderByUpdatedAtDesc(landlordId)
                .stream()
                .map(Listing::getId)
                .toList();

        if (listingIds.isEmpty()) {
            return List.of();
        }

        return applicationRepository
                .findAllByListingIdInOrderByCreatedAtDesc(listingIds)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public RentalApplicationResponse markUnderReview(
            UUID landlordId,
            UUID applicationId
    ) {
        RentalApplication application =
                findOwnedApplication(landlordId, applicationId);

        try {
            application.markUnderReview();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }

        return toResponse(application);
    }

    @Transactional
    public RentalApplicationResponse approve(
            UUID landlordId,
            UUID applicationId
    ) {
        RentalApplication application =
                findOwnedApplication(landlordId, applicationId);

        try {
            application.approve();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }

        return toResponse(application);
    }

    @Transactional
    public RentalApplicationResponse reject(
            UUID landlordId,
            UUID applicationId
    ) {
        RentalApplication application =
                findOwnedApplication(landlordId, applicationId);

        try {
            application.reject();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }

        return toResponse(application);
    }

    private RentalApplication findOwnedApplication(
            UUID landlordId,
            UUID applicationId
    ) {
        requireActiveLandlord(landlordId);

        RentalApplication application =
                applicationRepository.findById(applicationId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Application not found."
                        ));

        listingRepository.findByIdAndLandlordId(
                application.getListingId(),
                landlordId
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Application not found."
        ));

        return application;
    }

    private RentalApplicationResponse toResponse(
            RentalApplication application
    ) {
        return new RentalApplicationResponse(
                application.getId(),
                application.getListingId(),
                application.getStatus(),
                application.getCreatedAt(),
                application.getUpdatedAt()
        );
    }

    private void requireActiveLandlord(UUID landlordId) {
        User user = userRepository.findById(landlordId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Account is unavailable."
                ));

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account is not active."
            );
        }

        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                landlordId,
                RoleName.LANDLORD
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only landlords can manage applications."
            );
        }
    }

    private static ResponseStatusException conflict(
            String message
    ) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                message
        );
    }
}