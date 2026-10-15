package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.notification.domain.NotificationType;
import com.makaohub.backend.notification.service.NotificationService;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.renter.api.RentalApplicationResponse;
import com.makaohub.backend.renter.domain.SublessorRentalApplication;
import com.makaohub.backend.renter.repository.SublessorRentalApplicationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class SublessorApplicationReviewService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final SublessorListingRepository listingRepository;
    private final SublessorRentalApplicationRepository applicationRepository;
    private final NotificationService notificationService;

    public SublessorApplicationReviewService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            SublessorListingRepository listingRepository,
            SublessorRentalApplicationRepository applicationRepository,
            NotificationService notificationService
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.applicationRepository = applicationRepository;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    public List<RentalApplicationResponse> findForMyListings(
            UUID sublessorId
    ) {
        requireActiveSublessor(sublessorId);

        List<UUID> listingIds =
                listingRepository
                        .findAllBySublessorIdOrderByCreatedAtDesc(
                                sublessorId
                        )
                        .stream()
                        .map(SublessorListing::getId)
                        .toList();

        if (listingIds.isEmpty()) {
            return List.of();
        }

        return applicationRepository
                .findAllBySublessorListingIdInOrderByCreatedAtDesc(
                        listingIds
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public RentalApplicationResponse markUnderReview(
            UUID sublessorId,
            UUID applicationId
    ) {
        SublessorRentalApplication application =
                findOwnedApplication(
                        sublessorId,
                        applicationId
                );

        try {
            application.markUnderReview();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }

        notificationService.create(
                application.getRenterId(),
                NotificationType.APPLICATION_UNDER_REVIEW,
                "Application under review",
                "The sublessor is reviewing your room application."
        );

        return toResponse(application);
    }

    @Transactional
    public RentalApplicationResponse approve(
            UUID sublessorId,
            UUID applicationId
    ) {
        SublessorRentalApplication application =
                findOwnedApplication(
                        sublessorId,
                        applicationId
                );

        try {
            application.approve();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }

        notificationService.create(
                application.getRenterId(),
                NotificationType.APPLICATION_APPROVED,
                "Application approved",
                "Your room application has been approved."
        );

        return toResponse(application);
    }

    @Transactional
    public RentalApplicationResponse reject(
            UUID sublessorId,
            UUID applicationId,
            String reason
    ) {
        SublessorRentalApplication application =
                findOwnedApplication(
                        sublessorId,
                        applicationId
                );

        try {
            application.reject(reason);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }

        notificationService.create(
                application.getRenterId(),
                NotificationType.APPLICATION_REJECTED,
                "Application rejected",
                "Your room application was not approved."
        );

        return toResponse(application);
    }

    private SublessorRentalApplication findOwnedApplication(
            UUID sublessorId,
            UUID applicationId
    ) {
        requireActiveSublessor(sublessorId);

        SublessorRentalApplication application =
                applicationRepository.findById(applicationId)
                        .orElseThrow(
                                SublessorApplicationReviewService
                                        ::applicationNotFound
                        );

        listingRepository.findByIdAndSublessorId(
                application.getSublessorListingId(),
                sublessorId
        ).orElseThrow(
                SublessorApplicationReviewService
                        ::applicationNotFound
        );

        return application;
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

    private void requireActiveSublessor(
            UUID sublessorId
    ) {
        User user = userRepository.findById(sublessorId)
                .orElseThrow(
                        SublessorApplicationReviewService
                                ::accountUnavailable
                );

        if (user.getAccountStatus()
                != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account is not active."
            );
        }

        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                sublessorId,
                RoleName.SUBLESSOR
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only sublessors can review room applications."
            );
        }
    }

    private static ResponseStatusException applicationNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Application not found."
        );
    }

    private static ResponseStatusException conflict(
            String message
    ) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                message
        );
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}
