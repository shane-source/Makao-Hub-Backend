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
@RequestMapping("/api/v1/renter/roommate-chat/threads")
@Validated
public class RenterRoommateChatThreadController {

    private static final int MAX_THREAD_PAGE_SIZE = 50;
    private static final int MAX_MESSAGE_PAGE_SIZE = 100;

    private final RoommateChatThreadService threadService;
    private final RoommateChatMessageService messageService;
    private final RoommatePresenceService presenceService;

    public RenterRoommateChatThreadController(
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
     * authenticated Renter.
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
        return threadService.findForRenter(
                extractUserId(jwt),
                PageRequest.of(page, size)
        );
    }

    /**
     * Returns one thread only when it belongs to the authenticated
     * Renter.
     */
    @GetMapping("/{threadId}")
    public RoommateChatThreadResponse getThread(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID threadId
    ) {
        return threadService.findForRenter(
                extractUserId(jwt),
                threadId
        );
    }

    /**
     * Returns cursor-paginated message history.
     *
     * First page:
     *
     * GET /api/v1/renter/roommate-chat/threads/{threadId}/messages
     *     ?limit=50
     *
     * Older page:
     *
     * GET /api/v1/renter/roommate-chat/threads/{threadId}/messages
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
     * Returns whether the Sublessor in this thread is currently
     * online.
     *
     * The presence service independently verifies:
     *
     * - account status;
     * - Renter role;
     * - Renter verification;
     * - thread participation;
     * - open thread state;
     * - confirmed chat unlock.
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
     * Extracts the account identifier from the verified JWT subject.
     *
     * A user ID is never accepted from the client.
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