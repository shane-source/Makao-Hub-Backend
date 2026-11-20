package com.makaohub.backend.messaging.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.messaging.service.ChatThreadStateService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Archive, delete and restore for the signed-in person's own view of a conversation.
 * The three prefixes reuse the existing role rules, so each role is checked by the security configuration.
 */
@RestController
@RequestMapping({
        "/api/v1/renter/chat-state",
        "/api/v1/landlord/chat-state",
        "/api/v1/sublessor/chat-state"
})
public class ChatThreadStateController {

    public record ChangeStateRequest(String state) {
    }

    private final ChatThreadStateService service;

    public ChatThreadStateController(ChatThreadStateService service) {
        this.service = service;
    }

    @GetMapping
    public List<ChatThreadStateService.ThreadState> list(Authentication authentication) {
        return service.list(userId(authentication));
    }

    @PutMapping("/{kind}/{threadId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(
            Authentication authentication,
            @PathVariable String kind,
            @PathVariable UUID threadId,
            @RequestBody ChangeStateRequest request
    ) {
        service.change(userId(authentication), kind, threadId, request == null ? null : request.state());
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw unavailable();
        }

        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw unavailable();
        }
    }

    private static AuthException unavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}
