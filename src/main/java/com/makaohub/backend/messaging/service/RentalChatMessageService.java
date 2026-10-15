package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.messaging.api.RentalChatMessagePageResponse;
import com.makaohub.backend.messaging.api.RentalChatMessageResponse;
import com.makaohub.backend.messaging.api.SendRentalChatMessageRequest;
import com.makaohub.backend.messaging.domain.ChatThreadStatus;
import com.makaohub.backend.messaging.domain.RentalChatMessage;
import com.makaohub.backend.messaging.domain.RentalChatThread;
import com.makaohub.backend.messaging.repository.RentalChatMessageRepository;
import com.makaohub.backend.messaging.repository.RentalChatThreadRepository;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class RentalChatMessageService {

    private static final int MAX_HISTORY_SIZE = 100;
    private static final int MAX_CURSOR_LENGTH = 160;

    /*
     * Restrict client-supplied timestamps to a range supported by
     * PostgreSQL and ordinary four-digit-year API timestamps.
     */
    private static final Instant MIN_CURSOR_TIME =
            Instant.parse("0001-01-01T00:00:00Z");

    private static final Instant MAX_CURSOR_TIME =
            Instant.parse("9999-12-31T23:59:59.999999999Z");

    private final EntityManager entityManager;
    private final UserRoleRepository userRoleRepository;
    private final RoleVerificationRepository verificationRepository;
    private final RentalChatThreadRepository threadRepository;
    private final RentalChatMessageRepository messageRepository;
    private final ChatContactMaskingService maskingService;
    private final ChatMessageRateLimiter rateLimiter;

    public RentalChatMessageService(
            EntityManager entityManager,
            UserRoleRepository userRoleRepository,
            RoleVerificationRepository verificationRepository,
            RentalChatThreadRepository threadRepository,
            RentalChatMessageRepository messageRepository,
            ChatContactMaskingService maskingService,
            ChatMessageRateLimiter rateLimiter
    ) {
        this.entityManager = entityManager;
        this.userRoleRepository = userRoleRepository;
        this.verificationRepository = verificationRepository;
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.maskingService = maskingService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Authorizes, masks and saves one message.
     *
     * authenticatedUserId comes from the verified principal.
     * WebSocket delivery must occur after this transaction commits.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SentMessage send(
            UUID authenticatedUserId,
            SendRentalChatMessageRequest request
    ) {
        if (authenticatedUserId == null) {
            throw accountUnavailable();
        }

        validateRequest(request);
        rateLimiter.requirePermission(authenticatedUserId);
        requireActiveSenderWithLock(authenticatedUserId);

        RentalChatThread thread = threadRepository
                .findById(request.threadId())
                .orElseThrow(RentalChatMessageService::threadNotFound);

        RoleName senderRole = requireParticipant(
                authenticatedUserId,
                thread
        );

        requireVerifiedRole(authenticatedUserId, senderRole);

        /*
         * Hold the current thread state stable while saving.
         * A concurrent close must wait for this transaction.
         */
        entityManager.refresh(
                thread,
                LockModeType.PESSIMISTIC_READ
        );

        if (thread.getStatus() != ChatThreadStatus.OPEN) {
            throw conflict("Chat thread is closed.");
        }

        ChatContactMaskingService.MaskedMessage masked =
                maskingService.mask(request.content().strip());

        String safeContent = masked.content().strip();

        if (safeContent.isBlank()
                || safeContent.length()
                > RentalChatMessage.MAX_BODY_LENGTH) {
            throw badRequest(
                    "Message is empty or too long after contact masking."
            );
        }

        var existing = messageRepository
                .findBySenderIdAndClientMessageId(
                        authenticatedUserId,
                        request.clientMessageId()
                );

        if (existing.isPresent()) {
            RentalChatMessage message = existing.get();

            if (!message.getChatThreadId().equals(thread.getId())
                    || !message.getMaskedBody().equals(safeContent)
                    || message.isFlagged() != masked.contactMasked()) {
                throw conflict(
                        "Client message ID has already been used."
                );
            }

            return result(message, thread, false);
        }

        RentalChatMessage message = masked.contactMasked()
                ? RentalChatMessage.flagged(
                thread.getId(),
                authenticatedUserId,
                request.clientMessageId(),
                safeContent
        )
                : RentalChatMessage.clean(
                thread.getId(),
                authenticatedUserId,
                request.clientMessageId(),
                safeContent
        );

        RentalChatMessage saved =
                messageRepository.saveAndFlush(message);

        return result(saved, thread, true);
    }

    /**
     * Returns one slice of authorized message history, newest first.
     *
     * Closed conversations remain readable by eligible participants.
     * A cursor changes the position, never the authorized thread.
     */
    @Transactional(readOnly = true)
    public RentalChatMessagePageResponse history(
            UUID authenticatedUserId,
            UUID threadId,
            String cursor,
            int limit
    ) {
        if (authenticatedUserId == null) {
            throw accountUnavailable();
        }

        if (threadId == null) {
            throw badRequest("Thread ID is required.");
        }

        requireValidHistoryLimit(limit);

        User user = entityManager.find(
                User.class,
                authenticatedUserId
        );

        requireActiveAccount(user);

        RentalChatThread thread = threadRepository
                .findById(threadId)
                .orElseThrow(RentalChatMessageService::threadNotFound);

        RoleName readerRole = requireParticipant(
                authenticatedUserId,
                thread
        );

        requireVerifiedRole(authenticatedUserId, readerRole);

        MessageCursor position = decodeCursor(cursor);
        PageRequest firstSlice = PageRequest.of(0, limit);

        Slice<RentalChatMessage> slice;

        if (position == null) {
            slice = messageRepository
                    .findAllByChatThreadIdOrderByCreatedAtDescIdDesc(
                            threadId,
                            firstSlice
                    );
        } else {
            slice = messageRepository.findBeforeCursor(
                    threadId,
                    position.createdAt(),
                    position.id(),
                    firstSlice
            );
        }

        List<RentalChatMessageResponse> items =
                slice.getContent()
                        .stream()
                        .map(RentalChatMessageService::toResponse)
                        .toList();

        String nextCursor = null;

        if (slice.hasNext() && !slice.isEmpty()) {
            RentalChatMessage last =
                    slice.getContent().getLast();

            nextCursor = encodeCursor(
                    new MessageCursor(
                            last.getCreatedAt(),
                            last.getId()
                    )
            );
        }

        return new RentalChatMessagePageResponse(
                items,
                nextCursor,
                slice.hasNext()
        );
    }

    /**
     * Validates incoming content without exposing it in error messages.
     */
    private static void validateRequest(
            SendRentalChatMessageRequest request
    ) {
        if (request == null
                || request.threadId() == null
                || request.clientMessageId() == null) {
            throw badRequest(
                    "Thread ID and client message ID are required."
            );
        }

        if (request.content() == null
                || request.content().isBlank()
                || request.content().length()
                > RentalChatMessage.MAX_BODY_LENGTH) {
            throw badRequest(
                    "Message must contain between 1 and 2000 characters."
            );
        }
    }

    /**
     * Serializes rental-message sends from the same account so retries
     * check for an existing message after earlier sends complete.
     */
    private void requireActiveSenderWithLock(UUID userId) {
        User user = entityManager.find(
                User.class,
                userId,
                LockModeType.PESSIMISTIC_WRITE
        );

        requireActiveAccount(user);
    }

    /**
     * Shared account eligibility check for sending and history reads.
     */
    private static void requireActiveAccount(User user) {
        if (user == null) {
            throw accountUnavailable();
        }

        if (user.getAccountStatus() != AccountStatus.ACTIVE
                || user.isRoleSelectionRequired()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account is not eligible for chat."
            );
        }
    }

    /**
     * Derives the required role from stored thread participation.
     */
    private static RoleName requireParticipant(
            UUID userId,
            RentalChatThread thread
    ) {
        if (thread.getRenterId().equals(userId)) {
            return RoleName.RENTER;
        }

        if (thread.getLandlordId().equals(userId)) {
            return RoleName.LANDLORD;
        }

        throw threadNotFound();
    }

    /**
     * Checks the stored role assignment and its own verification.
     */
    private void requireVerifiedRole(
            UUID userId,
            RoleName requiredRole
    ) {
        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                userId,
                requiredRole
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "The required chat role is unavailable."
            );
        }

        boolean verified = verificationRepository
                .findByUserIdAndRole(userId, requiredRole)
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
     * Bounds the number of messages returned by one request.
     */
    private static void requireValidHistoryLimit(int limit) {
        if (limit < 1 || limit > MAX_HISTORY_SIZE) {
            throw badRequest(
                    "History limit must be between 1 and 100."
            );
        }
    }

    /**
     * Encodes only a stored timestamp and message UUID.
     * Base64 is an encoding, not encryption or authorization.
     */
    private static String encodeCursor(MessageCursor cursor) {
        String value = cursor.createdAt() + "|" + cursor.id();

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        value.getBytes(StandardCharsets.UTF_8)
                );
    }

    /**
     * Rejects oversized or malformed cursors before the history query.
     */
    private static MessageCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }

        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
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

            Instant createdAt = Instant.parse(parts[0]);
            UUID id = UUID.fromString(parts[1]);

            if (createdAt.isBefore(MIN_CURSOR_TIME)
                    || createdAt.isAfter(MAX_CURSOR_TIME)) {
                throw invalidCursor();
            }

            return new MessageCursor(createdAt, id);

        } catch (IllegalArgumentException
                 | DateTimeParseException exception) {
            throw invalidCursor();
        }
    }

    /**
     * Uses stored masked content for both send responses and history.
     */
    private static RentalChatMessageResponse toResponse(
            RentalChatMessage message
    ) {
        return new RentalChatMessageResponse(
                message.getId(),
                message.getChatThreadId(),
                message.getSenderId(),
                message.getClientMessageId(),
                message.getMaskedBody(),
                message.isFlagged(),
                message.getCreatedAt()
        );
    }

    /**
     * Adds internal recipient information for later delivery.
     */
    private static SentMessage result(
            RentalChatMessage message,
            RentalChatThread thread,
            boolean newlyCreated
    ) {
        return new SentMessage(
                toResponse(message),
                thread.getRenterId(),
                thread.getLandlordId(),
                newlyCreated
        );
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                reason
        );
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                reason
        );
    }

    private static ResponseStatusException invalidCursor() {
        return badRequest("Message cursor is invalid.");
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

    /**
     * Internal delivery result. Only message is sent to clients.
     */
    public record SentMessage(
            RentalChatMessageResponse message,
            UUID renterId,
            UUID landlordId,
            boolean newlyCreated
    ) {
    }
}