package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.notification.domain.NotificationType;
import com.makaohub.backend.notification.service.NotificationService;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.renter.api.RentalApplicationResponse;
import com.makaohub.backend.renter.domain.SublessorRentalApplication;
import com.makaohub.backend.renter.repository.SublessorRentalApplicationRepository;
import com.makaohub.backend.verification.service.RoleVerificationGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class SublessorRentalApplicationService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final SublessorListingRepository listingRepository;
    private final SublessorRentalApplicationRepository applicationRepository;
    private final NotificationService notificationService;
    private final RoleVerificationGuard verificationGuard;

    public SublessorRentalApplicationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            SublessorListingRepository listingRepository,
            SublessorRentalApplicationRepository applicationRepository,
            NotificationService notificationService,
            RoleVerificationGuard verificationGuard
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.applicationRepository = applicationRepository;
        this.notificationService = notificationService;
        this.verificationGuard = verificationGuard;
    }

    @Transactional
    public RentalApplicationResponse apply(
            UUID renterId,
            UUID sublessorListingId
    ) {
        requireActiveRenter(renterId);
        verificationGuard.requireVerified(renterId, RoleName.RENTER);

        SublessorListing listing =
                listingRepository.findById(sublessorListingId)
                        .orElseThrow(
                                SublessorRentalApplicationService
                                        ::listingNotFound
                        );

        if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw listingNotFound();
        }

        if (applicationRepository
                .existsByRenterIdAndSublessorListingId(
                        renterId,
                        sublessorListingId
                )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An application already exists for this listing."
            );
        }

        SublessorRentalApplication application =
                applicationRepository.save(
                        new SublessorRentalApplication(
                                renterId,
                                sublessorListingId
                        )
                );

        notificationService.create(
                listing.getSublessorId(),
                NotificationType.APPLICATION_SUBMITTED,
                "New room application",
                "A renter submitted an application for your room listing."
        );

        return toResponse(application);
    }

    @Transactional(readOnly = true)
    public List<RentalApplicationResponse> findMine(
            UUID renterId
    ) {
        requireActiveRenter(renterId);

        return applicationRepository
                .findAllByRenterIdOrderByCreatedAtDesc(renterId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RentalApplicationResponse findOne(
            UUID renterId,
            UUID applicationId
    ) {
        requireActiveRenter(renterId);

        SublessorRentalApplication application =
                applicationRepository.findByIdAndRenterId(
                        applicationId,
                        renterId
                ).orElseThrow(
                        SublessorRentalApplicationService
                                ::applicationNotFound
                );

        return toResponse(application);
    }

    @Transactional
    public RentalApplicationResponse withdraw(
            UUID renterId,
            UUID applicationId
    ) {
        requireActiveRenter(renterId);

        SublessorRentalApplication application =
                applicationRepository.findByIdAndRenterId(
                        applicationId,
                        renterId
                ).orElseThrow(
                        SublessorRentalApplicationService
                                ::applicationNotFound
                );

        try {
            application.withdraw();
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    exception.getMessage()
            );
        }

        return toResponse(application);
    }

    private RentalApplicationResponse toResponse(
            SublessorRentalApplication application
    ) {
        return new RentalApplicationResponse(
                application.getId(),
                application.getSublessorListingId(),
                application.getStatus(),
                application.getCreatedAt(),
                application.getUpdatedAt(),
                application.getRejectionReason()
        );
    }

    private void requireActiveRenter(UUID renterId) {
        User user = userRepository.findById(renterId)
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
                renterId,
                RoleName.RENTER
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only renters can manage applications."
            );
        }
    }

    private static ResponseStatusException listingNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Published sublessor listing not found."
        );
    }

    private static ResponseStatusException applicationNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Application not found."
        );
    }
}
