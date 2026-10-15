package com.makaohub.backend.roommate.service;

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
public class RenterRoommateInterestService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final SublessorListingRepository listingRepository;
    private final RoommateInterestRepository interestRepository;
    private final NotificationService notificationService;

    public RenterRoommateInterestService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            SublessorListingRepository listingRepository,
            RoommateInterestRepository interestRepository,
            NotificationService notificationService
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.listingRepository = listingRepository;
        this.interestRepository = interestRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public RoommateInterestResponse expressInterest(
            UUID renterId,
            UUID sublessorListingId
    ) {
        requireActiveRenter(renterId);

        SublessorListing listing =
                listingRepository.findById(sublessorListingId)
                        .orElseThrow(
                                RenterRoommateInterestService
                                        ::listingNotFound
                        );

        if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw listingNotFound();
        }

        if (interestRepository
                .existsByRenterIdAndSublessorListingId(
                        renterId,
                        sublessorListingId
                )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "You have already expressed interest in this room."
            );
        }

        RoommateInterest interest =
                interestRepository.save(
                        new RoommateInterest(
                                renterId,
                                sublessorListingId
                        )
                );

        notificationService.create(
                listing.getSublessorId(),
                NotificationType.ROOMMATE_INTEREST_SUBMITTED,
                "New roommate interest",
                "A renter is interested in becoming your roommate."
        );

        return toResponse(interest);
    }

    @Transactional(readOnly = true)
    public List<RoommateInterestResponse> findMine(
            UUID renterId
    ) {
        requireActiveRenter(renterId);

        return interestRepository
                .findAllByRenterIdOrderByCreatedAtDesc(renterId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RoommateInterestResponse findOne(
            UUID renterId,
            UUID interestId
    ) {
        requireActiveRenter(renterId);

        RoommateInterest interest =
                interestRepository.findByIdAndRenterId(
                        interestId,
                        renterId
                ).orElseThrow(
                        RenterRoommateInterestService
                                ::interestNotFound
                );

        return toResponse(interest);
    }

    @Transactional
    public RoommateInterestResponse withdraw(
            UUID renterId,
            UUID interestId
    ) {
        requireActiveRenter(renterId);

        RoommateInterest interest =
                interestRepository.findByIdAndRenterId(
                        interestId,
                        renterId
                ).orElseThrow(
                        RenterRoommateInterestService
                                ::interestNotFound
                );

        try {
            interest.withdraw();
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    exception.getMessage()
            );
        }

        return toResponse(interest);
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

    private void requireActiveRenter(UUID renterId) {
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
                    "Only renters can express roommate interest."
            );
        }
    }

    private static ResponseStatusException listingNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Published sublessor room not found."
        );
    }

    private static ResponseStatusException interestNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Roommate interest not found."
        );
    }
}