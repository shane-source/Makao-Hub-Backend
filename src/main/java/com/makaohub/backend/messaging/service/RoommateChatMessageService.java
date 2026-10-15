package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.messaging.api.RoommateChatMessagePageResponse;
import com.makaohub.backend.messaging.api.RoommateChatMessageResponse;
import com.makaohub.backend.messaging.api.SendRoommateChatMessageRequest;
import com.makaohub.backend.messaging.domain.RoommateChatMessage;
import com.makaohub.backend.messaging.domain.RoommateChatThread;
import com.makaohub.backend.messaging.domain.RoommateChatThreadStatus;
import com.makaohub.backend.messaging.repository.RoommateChatMessageRepository;
import com.makaohub.backend.messaging.repository.RoommateChatThreadRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class RoommateChatMessageService {

    private static final int MAXIMUM_HISTORY_PAGE_SIZE = 100;

    /*
     * A safe machine-readable reason is stored instead of the actual
     * detected phone number, email address or other contact detail.
     */
    private static final String CONTACT_DETAILS_REASON =
            "CONTACT_DETAILS";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoommateChatThreadRepository threadRepository;
    private final RoommateChatMessageRepository messageRepository;
    private final ChatContactMaskingService maskingService;
    private final ChatMessageRateLimiter rateLimiter;

    public RoommateChatMessageService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoommateChatThreadRepository threadRepository,
            RoommateChatMessageRepository messageRepository,
            ChatContactMaskingService maskingService,
            ChatMessageRateLimiter rateLimiter
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.maskingService = maskingService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Validates, masks, stores and returns one chat message.
     *
     * The sender ID comes from the authenticated WebSocket principal.
     * It is never accepted from the request body.
     */
    @Transactional
    public SentMessage send(
            UUID authenticatedUserId,
            SendRoommateChatMessageRequest request
    ) {
        requireActiveUser(authenticatedUserId);

        RoommateChatThread thread = requireAccessibleThread(
                authenticatedUserId,
                request.threadId()
        );

        requireOpenThread(thread);

        /*
         * Check idempotency before consuming a rate-limit token.
         *
         * A network retry using the same clientMessageId returns the
         * existing message instead of creating a duplicate.
         */
        var existingMessage = messageRepository
                .findBySenderIdAndClientMessageId(
                        authenticatedUserId,
                        request.clientMessageId()
                );

        if (existingMessage.isPresent()) {
            RoommateChatMessage existing =
                    existingMessage.get();

            if (!existing.getChatThreadId().equals(thread.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Client message ID has already been used."
                );
            }

            return new SentMessage(
                    toResponse(existing),
                    thread.getRenterId(),
                    thread.getSublessorId()
            );
        }

        rateLimiter.requirePermission(authenticatedUserId);

        RoommateChatMessageResponse response = saveNewMessage(
                authenticatedUserId,
                thread,
                request
        );

        return new SentMessage(
                response,
                thread.getRenterId(),
                thread.getSublessorId()
        );
    }

    /**
     * Returns one cursor-based page of message history.
     *
     * The authenticated user must be a participant in an open thread.
     */
    @Transactional(readOnly = true)
    public RoommateChatMessagePageResponse history(
            UUID authenticatedUserId,
            UUID threadId,
            String cursor,
            int limit
    ) {
        requireActiveUser(authenticatedUserId);

        RoommateChatThread thread = requireAccessibleThread(
                authenticatedUserId,
                threadId
        );

        requireOpenThread(thread);
        requireValidLimit(limit);

        MessageCursor decodedCursor = decodeCursor(cursor);

        Slice<RoommateChatMessage> slice;

        if (decodedCursor == null) {
            slice = messageRepository
                    .findAllByChatThreadIdOrderByCreatedAtDescIdDesc(
                            threadId,
                            PageRequest.of(0, limit)
                    );
        } else {
            slice = messageRepository.findBeforeCursor(
                    threadId,
                    decodedCursor.createdAt(),
                    decodedCursor.id(),
                    PageRequest.of(0, limit)
            );
        }

        List<RoommateChatMessageResponse> items =
                slice.getContent()
                        .stream()
                        .map(this::toResponse)
                        .toList();

        String nextCursor = null;

        if (slice.hasNext() && !slice.getContent().isEmpty()) {
            RoommateChatMessage lastMessage =
                    slice.getContent().getLast();

            nextCursor = encodeCursor(
                    new MessageCursor(
                            lastMessage.getCreatedAt(),
                            lastMessage.getId()
                    )
            );
        }

        return new RoommateChatMessagePageResponse(
                items,
                nextCursor,
                slice.hasNext()
        );
    }

    /**
     * Masks prohibited contact information before creating the entity.
     *
     * Phone numbers, email addresses, links and social handles are
     * replaced before persistence. The original content is never stored.
     */
    private RoommateChatMessageResponse saveNewMessage(
            UUID senderId,
            RoommateChatThread thread,
            SendRoommateChatMessageRequest request
    ) {
        ChatContactMaskingService.MaskedMessage masked =
                maskingService.mask(
                        request.content().strip()
                );

        RoommateChatMessage message;

        if (masked.contactMasked()) {
            message = RoommateChatMessage.flagged(
                    thread.getId(),
                    senderId,
                    request.clientMessageId(),
                    masked.content(),
                    CONTACT_DETAILS_REASON
            );
        } else {
            message = RoommateChatMessage.clean(
                    thread.getId(),
                    senderId,
                    request.clientMessageId(),
                    masked.content()
            );
        }

        RoommateChatMessage saved =
                messageRepository.save(message);

        return toResponse(saved);
    }

    /**
     * Confirms that the authenticated user belongs to the thread and
     * still owns the role associated with their side of the conversation.
     *
     * A nonparticipant receives 404 instead of confirmation that a
     * private thread exists.
     */
    private RoommateChatThread requireAccessibleThread(
            UUID userId,
            UUID threadId
    ) {
        RoommateChatThread thread =
                threadRepository.findById(threadId)
                        .orElseThrow(
                                RoommateChatMessageService
                                        ::threadNotFound
                        );

        if (thread.getRenterId().equals(userId)) {
            requireRole(userId, RoleName.RENTER);
            return thread;
        }

        if (thread.getSublessorId().equals(userId)) {
            requireRole(userId, RoleName.SUBLESSOR);
            return thread;
        }

        throw threadNotFound();
    }

    /**
     * Prevents sending or reading messages after the conversation is
     * closed.
     *
     * Chat does not require a paid entitlement.
     */
    private void requireOpenThread(
            RoommateChatThread thread
    ) {
        if (thread.getStatus()
                != RoommateChatThreadStatus.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Chat thread is closed."
            );
        }
    }

    /**
     * Confirms that the participant still owns the expected role.
     *
     * Returning 404 avoids revealing private conversation information
     * to accounts without the required role.
     */
    private void requireRole(
            UUID userId,
            RoleName role
    ) {
        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                userId,
                role
        )) {
            throw threadNotFound();
        }
    }

    /**
     * Blocks suspended, disabled and missing accounts.
     */
    private void requireActiveUser(
            UUID userId
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(
                        RoommateChatMessageService
                                ::accountUnavailable
                );

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account is not active."
            );
        }
    }

    /**
     * Protects the server from excessively large history requests.
     */
    private static void requireValidLimit(
            int limit
    ) {
        if (limit < 1
                || limit > MAXIMUM_HISTORY_PAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Message page size must be between 1 and "
                            + MAXIMUM_HISTORY_PAGE_SIZE
                            + "."
            );
        }
    }

    /**
     * Encodes the message timestamp and UUID into a URL-safe cursor.
     *
     * The cursor contains no message content or personal information.
     */
    private static String encodeCursor(
            MessageCursor cursor
    ) {
        String value = cursor.createdAt()
                + "|"
                + cursor.id();

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        value.getBytes(StandardCharsets.UTF_8)
                );
    }

    /**
     * Validates a client-supplied pagination cursor.
     *
     * Invalid Base64, timestamps and UUIDs produce a controlled
     * 400 response.
     */
    private static MessageCursor decodeCursor(
            String cursor
    ) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8
            );

            String[] parts = decoded.split("\\|", -1);

            if (parts.length != 2) {
                throw invalidCursor();
            }

            return new MessageCursor(
                    Instant.parse(parts[0]),
                    UUID.fromString(parts[1])
            );

        } catch (
                IllegalArgumentException
                | DateTimeParseException exception
        ) {
            throw invalidCursor();
        }
    }

    /**
     * Converts the database entity into the client-safe response.
     *
     * The moderation reason is not returned to ordinary participants.
     * contactMasked tells the frontend to display the message as
     * flagged.
     */
    private RoommateChatMessageResponse toResponse(
            RoommateChatMessage message
    ) {
        return new RoommateChatMessageResponse(
                message.getId(),
                message.getChatThreadId(),
                message.getSenderId(),
                message.getClientMessageId(),
                message.getMaskedBody(),
                message.isFlagged(),
                message.getCreatedAt()
        );
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Message cursor is invalid."
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

    private record MessageCursor(
            Instant createdAt,
            UUID id
    ) {
    }

    public record SentMessage(
            RoommateChatMessageResponse message,
            UUID renterId,
            UUID sublessorId
    ) {
    }
}