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
import com.makaohub.backend.renter.api.ApplicationSubmission;
import com.makaohub.backend.renter.api.RentalApplicationResponse;
import com.makaohub.backend.renter.domain.SublessorRentalApplication;
import com.makaohub.backend.renter.repository.SublessorRentalApplicationRepository;
import com.makaohub.backend.verification.service.RoleVerificationGuard;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    public SublessorRentalApplicationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            SublessorListingRepository listingRepository,
            SublessorRentalApplicationRepository applicationRepository,
            NotificationService notificationService,
            RoleVerificationGuard verificationGuard,
            JdbcTemplate jdbc
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.applicationRepository = applicationRepository;
        this.notificationService = notificationService;
        this.verificationGuard = verificationGuard;
        this.jdbc = jdbc;
    }

    @Transactional
    public RentalApplicationResponse apply(
            UUID renterId,
            UUID sublessorListingId,
            ApplicationSubmission submission
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

        UUID reopenedId = reopenSuperseded(renterId, sublessorListingId, submission);
        if (reopenedId != null) {
            SublessorRentalApplication reopened = applicationRepository.findById(reopenedId).orElseThrow();
            notificationService.create(
                    listing.getSublessorId(),
                    NotificationType.APPLICATION_SUBMITTED,
                    "Renter applied again",
                    "A renter applied again after you updated the listing. Approve to continue at the new price."
            );
            return toResponse(reopened);
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
                                sublessorListingId,
                                submission.moveInDate(),
                                submission.message()
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

    /**
     * After the owner changes the price or deposit, an approved application is removed from the renter's
     * list. Applying again reopens that same application for the owner to approve at the new terms.
     */
    private UUID reopenSuperseded(UUID renterId, UUID listingId, ApplicationSubmission submission) {
        List<UUID> ids = jdbc.query(
                "UPDATE sublessor_rental_applications a SET status = 'SUBMITTED', move_in_date = ?, message = ?, updated_at = CURRENT_TIMESTAMP "
                        + "FROM superseded_applications s "
                        + "WHERE s.kind = 'SUBLESSOR' AND s.application_id = a.id AND a.renter_id = ? AND a.sublessor_listing_id = ? "
                        + "AND a.status = 'WITHDRAWN' RETURNING a.id",
                (rs, row) -> rs.getObject(1, UUID.class),
                submission.moveInDate() == null ? null : java.sql.Date.valueOf(submission.moveInDate()),
                submission.message(), renterId, listingId);
        if (ids.isEmpty()) {
            return null;
        }
        UUID id = ids.get(0);
        jdbc.update("DELETE FROM superseded_applications WHERE kind = 'SUBLESSOR' AND application_id = ?", id);
        jdbc.update("DELETE FROM renter_application_states WHERE kind = 'SUBLESSOR' AND application_id = ? AND user_id = ?", id, renterId);
        return id;
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
                application.getRejectionReason(),
                null, null, null,
                application.getMoveInDate(),
                application.getMessage()
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
