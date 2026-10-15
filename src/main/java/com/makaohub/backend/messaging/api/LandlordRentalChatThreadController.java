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
 * Provides the authenticated Landlord's rental conversations,
 * paginated message history and presence information.
 *
 * SecurityConfig requires the LANDLORD role for this URL prefix.
 * The services enforce ownership, active account status and
 * role-specific verification.
 */
@RestController
@RequestMapping("/api/v1/landlord/rental-chat/threads")
public class LandlordRentalChatThreadController {

    private final RentalChatThreadService threadService;
    private final RentalChatMessageService messageService;
    private final RentalPresenceService presenceService;

    public LandlordRentalChatThreadController(
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
     * authenticated Landlord.
     */
    @GetMapping
    public PageResponse<RentalChatThreadResponse> getMyThreads(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(
                    name = "page",
                    defaultValue = "0"
            )
            int page,
            @RequestParam(
                    name = "size",
                    defaultValue = "20"
            )
            int size
    ) {
        return PageResponse.from(
                threadService.findForLandlord(
                        extractUserId(jwt),
                        page,
                        size
                )
        );
    }

    /**
     * Returns one conversation only when it belongs to the
     * authenticated Landlord.
     */
    @GetMapping("/{threadId}")
    public RentalChatThreadResponse getThread(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("threadId") UUID threadId
    ) {
        return threadService.findForLandlord(
                extractUserId(jwt),
                threadId
        );
    }

    /**
     * Returns cursor-paginated rental-chat messages.
     *
     * First page:
     *
     * GET /api/v1/landlord/rental-chat/threads/{threadId}/messages
     *
     * Older page:
     *
     * GET /api/v1/landlord/rental-chat/threads/{threadId}/messages
     *     ?cursor={nextCursor}
     */
    @GetMapping("/{threadId}/messages")
    public RentalChatMessagePageResponse getMessageHistory(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("threadId") UUID threadId,
            @RequestParam(
                    name = "cursor",
                    required = false
            )
            String cursor,
            @RequestParam(
                    name = "limit",
                    defaultValue = "50"
            )
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
     * Returns whether the Renter in this conversation is online.
     *
     * RentalPresenceService performs the ownership, role,
     * verification and open-thread checks.
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
     * Reads the user ID from the already validated JWT.
     *
     * The Landlord ID is never accepted from the request body or
     * query parameters.
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