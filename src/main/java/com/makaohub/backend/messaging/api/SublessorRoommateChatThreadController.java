package com.makaohub.backend.messaging.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.messaging.service.RoommateChatMessageService;
import com.makaohub.backend.messaging.service.RoommateChatThreadService;
import com.makaohub.backend.messaging.service.RoommatePresenceService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sublessor/roommate-chat/threads")
@Validated
public class SublessorRoommateChatThreadController {

    private static final int MAX_THREAD_PAGE_SIZE = 50;
    private static final int MAX_MESSAGE_PAGE_SIZE = 100;

    private final RoommateChatThreadService threadService;
    private final RoommateChatMessageService messageService;
    private final RoommatePresenceService presenceService;

    public SublessorRoommateChatThreadController(
            RoommateChatThreadService threadService,
            RoommateChatMessageService messageService,
            RoommatePresenceService presenceService
    ) {
        this.threadService = threadService;
        this.messageService = messageService;
        this.presenceService = presenceService;
    }

    /**
     * Returns one bounded page of chat threads belonging to the
     * authenticated Sublessor.
     *
     * Example:
     *
     * GET /api/v1/sublessor/roommate-chat/threads?page=0&size=20
     */
    @GetMapping
    public Page<RoommateChatThreadResponse> getMyThreads(
            @AuthenticationPrincipal Jwt jwt,

            @RequestParam(defaultValue = "0")
            @Min(
                    value = 0,
                    message = "Page number cannot be negative."
            )
            int page,

            @RequestParam(defaultValue = "20")
            @Min(
                    value = 1,
                    message = "Page size must be at least 1."
            )
            @Max(
                    value = MAX_THREAD_PAGE_SIZE,
                    message = "Page size cannot exceed 50."
            )
            int size
    ) {
        return threadService.findForSublessor(
                extractUserId(jwt),
                PageRequest.of(page, size)
        );
    }

    /**
     * Returns one thread only when the authenticated Sublessor owns
     * that thread.
     */
    @GetMapping("/{threadId}")
    public RoommateChatThreadResponse getThread(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID threadId
    ) {
        return threadService.findForSublessor(
                extractUserId(jwt),
                threadId
        );
    }

    /**
     * Returns cursor-paginated message history for an authorized
     * Sublessor.
     *
     * First page:
     *
     * GET /api/v1/sublessor/roommate-chat/threads/{threadId}/messages
     *     ?limit=50
     *
     * Older page:
     *
     * GET /api/v1/sublessor/roommate-chat/threads/{threadId}/messages
     *     ?limit=50
     *     &cursor={nextCursor}
     */
    @GetMapping("/{threadId}/messages")
    public RoommateChatMessagePageResponse getMessageHistory(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID threadId,

            @RequestParam(required = false)
            String cursor,

            @RequestParam(defaultValue = "50")
            @Min(
                    value = 1,
                    message = "Message page size must be at least 1."
            )
            @Max(
                    value = MAX_MESSAGE_PAGE_SIZE,
                    message = "Message page size cannot exceed 100."
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
     * Returns whether the Renter in this thread is currently online.
     *
     * RoommatePresenceService verifies the account, role verification,
     * thread participation, thread status and paid chat entitlement
     * before returning presence information.
     */
    @GetMapping("/{threadId}/presence")
    public RoommatePresenceResponse getOtherParticipantPresence(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID threadId
    ) {
        return presenceService.getOtherParticipantPresence(
                extractUserId(jwt),
                threadId
        );
    }

    /**
     * Extracts the permanent account identifier from the verified JWT.
     *
     * The Sublessor ID is never accepted as a request parameter,
     * preventing one user from impersonating another.
     */
    private UUID extractUserId(
            Jwt jwt
    ) {
        if (jwt == null || jwt.getSubject() == null) {
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