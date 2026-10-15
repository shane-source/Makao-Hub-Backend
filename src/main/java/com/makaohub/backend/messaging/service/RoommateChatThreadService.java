package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.messaging.api.RoommateChatThreadResponse;
import com.makaohub.backend.messaging.domain.RoommateChatThread;
import com.makaohub.backend.messaging.domain.RoommateChatThreadStatus;
import com.makaohub.backend.messaging.repository.RoommateChatThreadRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.roommate.domain.RoommateInterest;
import com.makaohub.backend.roommate.domain.RoommateInterestStatus;
import com.makaohub.backend.roommate.repository.RoommateInterestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class RoommateChatThreadService {

    /*
     * RECOMMENDED limits:
     *
     * Twenty records is a practical default for mobile screens.
     * Fifty is the server-enforced maximum, regardless of what the
     * client requests.
     */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoommateInterestRepository interestRepository;
    private final SublessorListingRepository listingRepository;
    private final RoommateChatThreadRepository threadRepository;

    public RoommateChatThreadService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoommateInterestRepository interestRepository,
            SublessorListingRepository listingRepository,
            RoommateChatThreadRepository threadRepository
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.interestRepository = interestRepository;
        this.listingRepository = listingRepository;
        this.threadRepository = threadRepository;
    }

    /**
     * Creates one chat thread after a Sublessor accepts a Renter's
     * roommate interest.
     *
     * The unique database constraint on roommateInterestId prevents
     * duplicate chat threads.
     */
    @Transactional
    public RoommateChatThread createForAcceptedInterest(
            UUID sublessorId,
            UUID interestId
    ) {
        requireActiveSublessor(sublessorId);

        RoommateInterest interest =
                interestRepository.findById(interestId)
                        .orElseThrow(
                                RoommateChatThreadService
                                        ::interestNotFound
                        );

        if (interest.getStatus()
                != RoommateInterestStatus.ACCEPTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Chat can only be created after the roommate interest is accepted."
            );
        }

        /*
         * This ownership query proves that the authenticated Sublessor
         * owns the listing connected to the accepted interest.
         */
        listingRepository.findByIdAndSublessorId(
                interest.getSublessorListingId(),
                sublessorId
        ).orElseThrow(
                RoommateChatThreadService::interestNotFound
        );

        /*
         * Repeated requests return the existing thread instead of
         * deliberately creating another one.
         */
        return threadRepository
                .findByRoommateInterestId(interestId)
                .orElseGet(
                        () -> threadRepository.save(
                                new RoommateChatThread(
                                        interestId,
                                        interest.getRenterId(),
                                        sublessorId
                                )
                        )
                );
    }

    /**
     * Temporary compatibility method for the current Renter controller.
     *
     * It now returns only the first bounded page instead of loading
     * every thread into memory. It can be removed after the controller
     * accepts page and size parameters.
     */
    @Transactional(readOnly = true)
    public List<RoommateChatThreadResponse> findForRenter(
            UUID renterId
    ) {
        return findForRenter(
                renterId,
                PageRequest.of(0, DEFAULT_PAGE_SIZE)
        ).getContent();
    }

    /**
     * Returns one bounded page of threads owned by the Renter.
     */
    @Transactional(readOnly = true)
    public Page<RoommateChatThreadResponse> findForRenter(
            UUID renterId,
            Pageable requestedPage
    ) {
        requireActiveRenter(renterId);

        Pageable boundedPage = boundPageable(requestedPage);

        return threadRepository
                .findAllByRenterIdOrderByCreatedAtDesc(
                        renterId,
                        boundedPage
                )
                .map(this::toResponse);
    }

    /**
     * Returns one thread only when it belongs to the authenticated
     * Renter.
     */
    @Transactional(readOnly = true)
    public RoommateChatThreadResponse findForRenter(
            UUID renterId,
            UUID threadId
    ) {
        requireActiveRenter(renterId);

        RoommateChatThread thread =
                threadRepository.findByIdAndRenterId(
                        threadId,
                        renterId
                ).orElseThrow(
                        RoommateChatThreadService::threadNotFound
                );

        return toResponse(thread);
    }

    /**
     * Temporary compatibility method for the current Sublessor
     * controller.
     *
     * It returns only the first bounded page.
     */
    @Transactional(readOnly = true)
    public List<RoommateChatThreadResponse> findForSublessor(
            UUID sublessorId
    ) {
        return findForSublessor(
                sublessorId,
                PageRequest.of(0, DEFAULT_PAGE_SIZE)
        ).getContent();
    }

    /**
     * Returns one bounded page of threads owned by the Sublessor.
     */
    @Transactional(readOnly = true)
    public Page<RoommateChatThreadResponse> findForSublessor(
            UUID sublessorId,
            Pageable requestedPage
    ) {
        requireActiveSublessor(sublessorId);

        Pageable boundedPage = boundPageable(requestedPage);

        return threadRepository
                .findAllBySublessorIdOrderByCreatedAtDesc(
                        sublessorId,
                        boundedPage
                )
                .map(this::toResponse);
    }

    /**
     * Returns one thread only when it belongs to the authenticated
     * Sublessor.
     */
    @Transactional(readOnly = true)
    public RoommateChatThreadResponse findForSublessor(
            UUID sublessorId,
            UUID threadId
    ) {
        requireActiveSublessor(sublessorId);

        RoommateChatThread thread =
                threadRepository.findByIdAndSublessorId(
                        threadId,
                        sublessorId
                ).orElseThrow(
                        RoommateChatThreadService::threadNotFound
                );

        return toResponse(thread);
    }

    /**
     * Enforces pagination limits on the server.
     *
     * A client cannot bypass the maximum by requesting a very large
     * page size.
     */
    private Pageable boundPageable(
            Pageable requestedPage
    ) {
        if (requestedPage == null) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE);
        }

        int pageNumber = Math.max(
                requestedPage.getPageNumber(),
                0
        );

        int requestedSize = Math.max(
                requestedPage.getPageSize(),
                1
        );

        int boundedSize = Math.min(
                requestedSize,
                MAX_PAGE_SIZE
        );

        return PageRequest.of(
                pageNumber,
                boundedSize
        );
    }

    /**
     * Converts the database entity into a safe client response.
     *
     * Internal participant identifiers and payment information are
     * not exposed through this response.
     */
    private RoommateChatThreadResponse toResponse(
            RoommateChatThread thread
    ) {
        return new RoommateChatThreadResponse(
                thread.getId(),
                thread.getRoommateInterestId(),
                thread.getStatus(),
                thread.getStatus()
                        == RoommateChatThreadStatus.OPEN,
                thread.getCreatedAt(),
                thread.getUpdatedAt(),
                thread.getClosedAt()
        );
    }

    /**
     * Confirms that the account is active and owns the RENTER role.
     *
     * This service-level check remains necessary even though
     * SecurityConfig also checks the HTTP route.
     */
    private void requireActiveRenter(
            UUID renterId
    ) {
        User user = userRepository.findById(renterId)
                .orElseThrow(
                        RoommateChatThreadService
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
                renterId,
                RoleName.RENTER
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only renters can access renter chat threads."
            );
        }
    }

    /**
     * Confirms that the account is active and owns the SUBLESSOR role.
     */
    private void requireActiveSublessor(
            UUID sublessorId
    ) {
        User user = userRepository.findById(sublessorId)
                .orElseThrow(
                        RoommateChatThreadService
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
                    "Only sublessors can access sublessor chat threads."
            );
        }
    }

    private static ResponseStatusException interestNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Roommate interest not found."
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