package com.makaohub.backend.messaging.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.messaging.service.RentalChatMessageService;
import com.makaohub.backend.messaging.service.RentalChatThreadService;
import com.makaohub.backend.messaging.service.RentalPresenceService;
import com.makaohub.backend.shared.api.PageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Provides the authenticated Renter's Landlord conversations,
 * message history and authorized presence information.
 *
 * SecurityConfig requires the RENTER role for this URL prefix.
 * Services independently enforce account eligibility, verification
 * and conversation ownership.
 */
@RestController
@RequestMapping("/api/v1/renter/rental-chat/threads")
public class RenterRentalChatThreadController {

    private final RentalChatThreadService threadService;
    private final RentalChatMessageService messageService;
    private final RentalPresenceService presenceService;

    public RenterRentalChatThreadController(
            RentalChatThreadService threadService,
            RentalChatMessageService messageService,
            RentalPresenceService presenceService
    ) {
        this.threadService = threadService;
        this.messageService = messageService;
        this.presenceService = presenceService;
    }

    /**
     * Returns one bounded page of conversations belonging to the
     * authenticated Renter.
     */
    @GetMapping
    public PageResponse<RentalChatThreadResponse> getMyThreads(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "page", defaultValue = "0")
            int page,
            @RequestParam(name = "size", defaultValue = "20")
            int size
    ) {
        return PageResponse.from(
                threadService.findForRenter(
                        extractUserId(jwt),
                        page,
                        size
                )
        );
    }

    /**
     * Returns one conversation only after the service confirms that
     * it belongs to the authenticated Renter.
     */
    @GetMapping("/{threadId}")
    public RentalChatThreadResponse getThread(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("threadId") UUID threadId
    ) {
        return threadService.findForRenter(
                extractUserId(jwt),
                threadId
        );
    }

    /**
     * Returns messages newest first.
     *
     * Omit cursor for the newest slice. Supply nextCursor from the
     * previous response to retrieve older messages.
     */
    @GetMapping("/{threadId}/messages")
    public RentalChatMessagePageResponse getMessageHistory(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("threadId") UUID threadId,
            @RequestParam(name = "cursor", required = false)
            String cursor,
            @RequestParam(name = "limit", defaultValue = "50")
            int limit
    ) {
        return messageService.history(
                extractUserId(jwt),
                threadId,
                cursor,
                limit
        );
    }

    /**
     * Returns whether the Landlord in this conversation is currently
     * online.
     *
     * RentalPresenceService verifies the Renter's account, active
     * role, verification, thread ownership and open-thread status.
     */
    @GetMapping("/{threadId}/presence")
    public RentalPresenceResponse getOtherParticipantPresence(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("threadId") UUID threadId
    ) {
        return presenceService.getOtherParticipantPresence(
                extractUserId(jwt),
                threadId
        );
    }

    /**
     * Reads the account identifier from the JWT already validated by
     * Spring Security.
     *
     * The controller never accepts a user ID from the request.
     */
    private static UUID extractUserId(Jwt jwt) {
        if (jwt == null
                || jwt.getSubject() == null
                || jwt.getSubject().isBlank()) {
            throw accountUnavailable();
        }

        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw accountUnavailable();
        }
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}