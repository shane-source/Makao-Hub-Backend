package com.makaohub.backend.roommate.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.messaging.service.RoommateChatThreadService;
import com.makaohub.backend.notification.domain.NotificationType;
import com.makaohub.backend.notification.service.NotificationService;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.roommate.api.RoommateInterestResponse;
import com.makaohub.backend.roommate.domain.RoommateInterest;
import com.makaohub.backend.roommate.repository.RoommateInterestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class SublessorRoommateInterestReviewService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final SublessorListingRepository listingRepository;
    private final RoommateInterestRepository interestRepository;
    private final NotificationService notificationService;
    private final RoommateChatThreadService chatThreadService;

    public SublessorRoommateInterestReviewService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            SublessorListingRepository listingRepository,
            RoommateInterestRepository interestRepository,
            NotificationService notificationService,
            RoommateChatThreadService chatThreadService
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.interestRepository = interestRepository;
        this.notificationService = notificationService;
        this.chatThreadService = chatThreadService;
    }

    @Transactional(readOnly = true)
    public List<RoommateInterestResponse> findForMyListings(
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

        return interestRepository
                .findAllBySublessorListingIdInOrderByCreatedAtDesc(
                        listingIds
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public RoommateInterestResponse markUnderReview(
            UUID sublessorId,
            UUID interestId
    ) {
        RoommateInterest interest =
                findOwnedInterest(
                        sublessorId,
                        interestId
                );

        try {
            interest.markUnderReview();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }

        notificationService.create(
                interest.getRenterId(),
                NotificationType.ROOMMATE_INTEREST_UNDER_REVIEW,
                "Roommate interest under review",
                "The sublessor is reviewing your roommate interest."
        );

        return toResponse(interest);
    }

    @Transactional
    public RoommateInterestResponse accept(
            UUID sublessorId,
            UUID interestId
    ) {
        RoommateInterest interest =
                findOwnedInterest(
                        sublessorId,
                        interestId
                );

        try {
            interest.accept();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }

        /*
         * The thread is deliberately created as LOCKED.
         * Acceptance does not grant chat access.
         * A confirmed chat-unlock payment is required later.
         */
        chatThreadService.createForAcceptedInterest(
                sublessorId,
                interestId
        );

        notificationService.create(
                interest.getRenterId(),
                NotificationType.ROOMMATE_INTEREST_ACCEPTED,
                "Roommate interest accepted",
                "The sublessor accepted your roommate interest."
        );

        return toResponse(interest);
    }

    @Transactional
    public RoommateInterestResponse decline(
            UUID sublessorId,
            UUID interestId
    ) {
        RoommateInterest interest =
                findOwnedInterest(
                        sublessorId,
                        interestId
                );

        try {
            interest.decline();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }

        notificationService.create(
                interest.getRenterId(),
                NotificationType.ROOMMATE_INTEREST_DECLINED,
                "Roommate interest declined",
                "The sublessor did not accept your roommate interest."
        );

        return toResponse(interest);
    }

    private RoommateInterest findOwnedInterest(
            UUID sublessorId,
            UUID interestId
    ) {
        requireActiveSublessor(sublessorId);

        RoommateInterest interest =
                interestRepository.findById(interestId)
                        .orElseThrow(
                                SublessorRoommateInterestReviewService
                                        ::interestNotFound
                        );

        listingRepository.findByIdAndSublessorId(
                interest.getSublessorListingId(),
                sublessorId
        ).orElseThrow(
                SublessorRoommateInterestReviewService
                        ::interestNotFound
        );

        return interest;
    }

    private RoommateInterestResponse toResponse(
            RoommateInterest interest
    ) {
        return new RoommateInterestResponse(
                interest.getId(),
                interest.getSublessorListingId(),
                interest.getStatus(),
                interest.getCreatedAt(),
                interest.getUpdatedAt()
        );
    }

    private void requireActiveSublessor(
            UUID sublessorId
    ) {
        User user = userRepository.findById(sublessorId)
                .orElseThrow(
                        SublessorRoommateInterestReviewService
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
                    "Only sublessors can review roommate interest."
            );
        }
    }

    private static ResponseStatusException interestNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Roommate interest not found."
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