package com.makaohub.backend.messaging.service;

import com.makaohub.backend.messaging.domain.RoommateChatThread;
import com.makaohub.backend.messaging.domain.RoommateChatThreadStatus;
import com.makaohub.backend.messaging.repository.RoommateChatThreadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RoommatePresenceListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RoommatePresenceListener.class);

    private static final int PAGE_SIZE = 50;

    private final RoommateChatThreadRepository threadRepository;
    private final RoommatePresenceRegistry sessionRegistry;
    private final RoommatePresencePublisher publisher;

    public RoommatePresenceListener(
            RoommateChatThreadRepository threadRepository,
            RoommatePresenceRegistry sessionRegistry,
            RoommatePresencePublisher publisher
    ) {
        this.threadRepository = threadRepository;
        this.sessionRegistry = sessionRegistry;
        this.publisher = publisher;
    }

    /**
     * Recomputes roommate-chat presence after a socket connects,
     * disconnects, or is removed during JWT cleanup.
     */
    @EventListener
    public void onPresenceChanged(
            RoommatePresenceRegistry.PresenceChanged event
    ) {
        if (event == null || event.userId() == null) {
            return;
        }

        try {
            publishRenterThreads(event.userId());
            publishSublessorThreads(event.userId());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Roommate presence update failed; errorType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void publishRenterThreads(UUID renterId) {
        publishForRenter(renterId, PageRequest.of(0, PAGE_SIZE));
    }

    private void publishForRenter(UUID renterId, Pageable pageable) {
        Page<RoommateChatThread> threads = threadRepository
                .findAllByRenterIdOrderByCreatedAtDesc(renterId, pageable);

        for (RoommateChatThread thread : threads.getContent()) {
            if (thread.getStatus() == RoommateChatThreadStatus.OPEN) {
                publishToParticipant(
                        thread.getSublessorId(),
                        thread.getId()
                );
            }
        }

        if (threads.hasNext()) {
            publishForRenter(renterId, threads.nextPageable());
        }
    }

    private void publishSublessorThreads(UUID sublessorId) {
        publishForSublessor(
                sublessorId,
                PageRequest.of(0, PAGE_SIZE)
        );
    }

    private void publishForSublessor(
            UUID sublessorId,
            Pageable pageable
    ) {
        Page<RoommateChatThread> threads = threadRepository
                .findAllBySublessorIdOrderByCreatedAtDesc(
                        sublessorId,
                        pageable
                );

        for (RoommateChatThread thread : threads.getContent()) {
            if (thread.getStatus() == RoommateChatThreadStatus.OPEN) {
                publishToParticipant(
                        thread.getRenterId(),
                        thread.getId()
                );
            }
        }

        if (threads.hasNext()) {
            publishForSublessor(sublessorId, threads.nextPageable());
        }
    }

    private void publishToParticipant(
            UUID participantId,
            UUID threadId
    ) {
        for (RoommatePresenceRegistry.AuthenticatedSession session
                : sessionRegistry.sessionsFor(participantId)) {
            try {
                publisher.publishToSession(
                        threadId,
                        session.sessionId(),
                        session.jwt()
                );
            } catch (MessagingException exception) {
                LOGGER.warn(
                        "Roommate presence delivery failed; errorType={}",
                        exception.getClass().getSimpleName()
                );
            }
        }
    }
}
