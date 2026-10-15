package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.messaging.api.RentalChatThreadResponse;
import com.makaohub.backend.messaging.domain.RentalChatThread;
import com.makaohub.backend.messaging.repository.RentalChatThreadRepository;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.renter.domain.RentalApplication;
import com.makaohub.backend.renter.domain.RentalApplicationStatus;
import com.makaohub.backend.renter.repository.RentalApplicationRepository;
import com.makaohub.backend.shared.api.PaginationPolicy;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class RentalChatThreadService {

    private static final int MAXIMUM_PAGE_SIZE = 50;

    /*
     * Repository methods already sort by createdAt descending.
     * This secondary sort makes equal timestamps deterministic.
     */
    private static final Sort THREAD_TIE_BREAKER =
            Sort.by(Sort.Direction.DESC, "id");

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleVerificationRepository verificationRepository;
    private final RentalApplicationRepository applicationRepository;
    private final ListingRepository listingRepository;
    private final RentalChatThreadRepository threadRepository;

    public RentalChatThreadService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleVerificationRepository verificationRepository,
            RentalApplicationRepository applicationRepository,
            ListingRepository listingRepository,
            RentalChatThreadRepository threadRepository
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.verificationRepository = verificationRepository;
        this.applicationRepository = applicationRepository;
        this.listingRepository = listingRepository;
        this.threadRepository = threadRepository;
    }

    /**
     * Creates a conversation for a submitted rental application.
     *
     * renterId must come from the authenticated principal.
     * The Landlord is derived from the application's listing.
     *
     * Joins the application submission transaction when called from
     * RentalApplicationService.
     */
    @Transactional
    public RentalChatThread createForApplication(
            UUID renterId,
            UUID applicationId
    ) {
        requireEligibleUser(renterId, RoleName.RENTER);

        RentalApplication application =
                applicationRepository.findByIdAndRenterId(
                        applicationId,
                        renterId
                ).orElseThrow(
                        RentalChatThreadService::applicationNotFound
                );

        if (application.getStatus()
                != RentalApplicationStatus.SUBMITTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Chat can only be created for a submitted application."
            );
        }

        Listing listing = listingRepository
                .findById(application.getListingId())
                .orElseThrow(
                        RentalChatThreadService::applicationNotFound
                );

        UUID landlordId = listing.getLandlordId();

        if (renterId.equals(landlordId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "You cannot create a rental chat with yourself."
            );
        }

        requireEligibleUser(landlordId, RoleName.LANDLORD);

        var existing = threadRepository
                .findByRentalApplicationId(applicationId);

        if (existing.isPresent()) {
            RentalChatThread thread = existing.get();

            /*
             * Fail closed if stored participants no longer match
             * the application and listing.
             */
            if (!thread.getRenterId().equals(renterId)
                    || !thread.getLandlordId().equals(landlordId)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Conversation participants do not match the application."
                );
            }

            return thread;
        }

        /*
         * The database UNIQUE constraint remains the final protection
         * against concurrent creation of duplicate threads.
         */
        return threadRepository.save(
                new RentalChatThread(
                        applicationId,
                        renterId,
                        landlordId
                )
        );
    }

    /**
     * Returns a bounded page containing only this Renter's threads.
     *
     * Page numbering starts at zero.
     */
    @Transactional(readOnly = true)
    public Page<RentalChatThreadResponse> findForRenter(
            UUID renterId,
            int page,
            int size
    ) {
        requireEligibleUser(renterId, RoleName.RENTER);

        Pageable pageable = threadPage(page, size);

        return threadRepository
                .findAllByRenterIdOrderByCreatedAtDesc(
                        renterId,
                        pageable
                )
                .map(this::toResponse);
    }

    /**
     * Returns one thread only if the Renter owns it.
     */
    @Transactional(readOnly = true)
    public RentalChatThreadResponse findForRenter(
            UUID renterId,
            UUID threadId
    ) {
        requireEligibleUser(renterId, RoleName.RENTER);

        RentalChatThread thread = threadRepository
                .findByIdAndRenterId(threadId, renterId)
                .orElseThrow(
                        RentalChatThreadService::threadNotFound
                );

        return toResponse(thread);
    }

    /**
     * Returns a bounded page containing only this Landlord's threads.
     */
    @Transactional(readOnly = true)
    public Page<RentalChatThreadResponse> findForLandlord(
            UUID landlordId,
            int page,
            int size
    ) {
        requireEligibleUser(landlordId, RoleName.LANDLORD);

        Pageable pageable = threadPage(page, size);

        return threadRepository
                .findAllByLandlordIdOrderByCreatedAtDesc(
                        landlordId,
                        pageable
                )
                .map(this::toResponse);
    }

    /**
     * Returns one thread only if the Landlord owns it.
     */
    @Transactional(readOnly = true)
    public RentalChatThreadResponse findForLandlord(
            UUID landlordId,
            UUID threadId
    ) {
        requireEligibleUser(landlordId, RoleName.LANDLORD);

        RentalChatThread thread = threadRepository
                .findByIdAndLandlordId(threadId, landlordId)
                .orElseThrow(
                        RentalChatThreadService::threadNotFound
                );

        return toResponse(thread);
    }

    /**
     * Requires an active account with completed role selection,
     * the expected role assignment and verification for that role.
     *
     * Callers choose the expected role in backend code.
     */
    private void requireEligibleUser(
            UUID userId,
            RoleName expectedRole
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(
                        RentalChatThreadService::accountUnavailable
                );

        if (user.getAccountStatus() != AccountStatus.ACTIVE
                || user.isRoleSelectionRequired()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account is not eligible for chat."
            );
        }

        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                userId,
                expectedRole
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "The required chat role is unavailable."
            );
        }

        boolean verified = verificationRepository
                .findByUserIdAndRole(userId, expectedRole)
                .map(verification ->
                        verification.getStatus()
                                == VerificationStatus.VERIFIED
                )
                .orElse(false);

        if (!verified) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "A verified role is required for chat."
            );
        }
    }

    /**
     * Reuses the existing pagination policy.
     *
     * Invalid page numbers or sizes produce a controlled 400 response.
     */
    private static Pageable threadPage(
            int page,
            int size
    ) {
        return PaginationPolicy.pageRequest(
                page,
                size,
                MAXIMUM_PAGE_SIZE,
                THREAD_TIE_BREAKER
        );
    }

    /**
     * Explicitly selects the fields returned to clients.
     *
     * This mapper is called only after access checks.
     */
    private RentalChatThreadResponse toResponse(
            RentalChatThread thread
    ) {
        return new RentalChatThreadResponse(
                thread.getId(),
                thread.getRentalApplicationId(),
                thread.getStatus(),
                thread.getCreatedAt(),
                thread.getUpdatedAt(),
                thread.getClosedAt()
        );
    }

    private static ResponseStatusException applicationNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Rental application not found."
        );
    }

    private static ResponseStatusException threadNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Chat thread not found."
        );
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}