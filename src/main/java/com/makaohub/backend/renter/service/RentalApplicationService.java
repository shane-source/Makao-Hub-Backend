package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.messaging.service.RentalChatThreadService;
import com.makaohub.backend.notification.domain.NotificationType;
import com.makaohub.backend.notification.service.NotificationService;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.renter.api.RentalApplicationResponse;
import com.makaohub.backend.renter.domain.RentalApplication;
import com.makaohub.backend.renter.repository.RentalApplicationRepository;
import com.makaohub.backend.verification.service.RoleVerificationGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class RentalApplicationService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ListingRepository listingRepository;
    private final RentalApplicationRepository applicationRepository;
    private final RentalChatThreadService rentalChatThreadService;
    private final NotificationService notificationService;
    private final RoleVerificationGuard verificationGuard;

    public RentalApplicationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            ListingRepository listingRepository,
            RentalApplicationRepository applicationRepository,
            RentalChatThreadService rentalChatThreadService,
            NotificationService notificationService,
            RoleVerificationGuard verificationGuard
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.applicationRepository = applicationRepository;
        this.rentalChatThreadService = rentalChatThreadService;
        this.notificationService = notificationService;
        this.verificationGuard = verificationGuard;
    }

    /**
     * Creates a rental application and its open Landlord chat in the
     * same database transaction.
     *
     * If either operation fails, the application and chat are rolled
     * back together.
     */
    @Transactional
    public RentalApplicationResponse apply(
            UUID renterId,
            UUID listingId
    ) {
        requireActiveRenter(renterId);
        verificationGuard.requireVerified(renterId, RoleName.RENTER);

        Listing listing = listingRepository.findByIdAndStatus(
                listingId,
                ListingStatus.PUBLISHED
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Published listing not found."
        ));

        if (applicationRepository.existsByRenterIdAndListingId(
                renterId,
                listing.getId()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An application already exists for this listing."
            );
        }

        RentalApplication application =
                applicationRepository.save(
                        new RentalApplication(
                                renterId,
                                listingId
                        )
                );

        /*
         * The chat service derives the Landlord from the listing.
         * The client never supplies or controls the landlord ID.
         */
        rentalChatThreadService.createForApplication(
                renterId,
                application.getId()
        );

        notificationService.create(
                listing.getLandlordId(),
                NotificationType.APPLICATION_SUBMITTED,
                "New rental application",
                "A renter submitted an application for your listing."
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

        RentalApplication application =
                applicationRepository.findByIdAndRenterId(
                        applicationId,
                        renterId
                ).orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Application not found."
                ));

        return toResponse(application);
    }

    @Transactional
    public RentalApplicationResponse withdraw(
            UUID renterId,
            UUID applicationId
    ) {
        requireActiveRenter(renterId);

        RentalApplication application =
                applicationRepository.findByIdAndRenterId(
                        applicationId,
                        renterId
                ).orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Application not found."
                ));

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

    /**
     * Confirms that the caller is an active Renter.
     */
    private void requireActiveRenter(
            UUID renterId
    ) {
        User user = userRepository.findById(renterId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Account is unavailable."
                ));

        if (user.getAccountStatus()
                != AccountStatus.ACTIVE) {
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
}
