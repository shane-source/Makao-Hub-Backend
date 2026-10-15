package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.messaging.domain.ChatThreadStatus;
import com.makaohub.backend.messaging.domain.RentalChatThread;
import com.makaohub.backend.messaging.repository.RentalChatThreadRepository;
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
public class RentalPresenceListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RentalPresenceListener.class);

    private static final int PAGE_SIZE = 50;

    private final RentalChatThreadRepository threadRepository;
    private final RoommatePresenceRegistry sessionRegistry;
    private final RentalPresencePublisher publisher;

    public RentalPresenceListener(
            RentalChatThreadRepository threadRepository,
            RoommatePresenceRegistry sessionRegistry,
            RentalPresencePublisher publisher
    ) {
        this.threadRepository = threadRepository;
        this.sessionRegistry = sessionRegistry;
        this.publisher = publisher;
    }

    /**
     * Recomputes rental-chat presence after a connection, disconnection,
     * or invalid-session cleanup event.
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
            publishLandlordThreads(event.userId());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Rental presence update failed; errorType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void publishRenterThreads(UUID renterId) {
        Pageable pageable = PageRequest.of(0, PAGE_SIZE);

        while (true) {
            Page<RentalChatThread> threads = threadRepository
                    .findAllByRenterIdAndStatusOrderByCreatedAtDescIdDesc(
                            renterId,
                            ChatThreadStatus.OPEN,
                            pageable
                    );

            for (RentalChatThread thread : threads.getContent()) {
                publishToParticipant(
                        thread.getLandlordId(),
                        RoleName.LANDLORD,
                        thread.getId()
                );
            }

            if (!threads.hasNext()) {
                return;
            }

            pageable = threads.nextPageable();
        }
    }

    private void publishLandlordThreads(UUID landlordId) {
        Pageable pageable = PageRequest.of(0, PAGE_SIZE);

        while (true) {
            Page<RentalChatThread> threads = threadRepository
                    .findAllByLandlordIdAndStatusOrderByCreatedAtDescIdDesc(
                            landlordId,
                            ChatThreadStatus.OPEN,
                            pageable
                    );

            for (RentalChatThread thread : threads.getContent()) {
                publishToParticipant(
                        thread.getRenterId(),
                        RoleName.RENTER,
                        thread.getId()
                );
            }

            if (!threads.hasNext()) {
                return;
            }

            pageable = threads.nextPageable();
        }
    }

    private void publishToParticipant(
            UUID recipientId,
            RoleName requiredRole,
            UUID threadId
    ) {
        for (RoommatePresenceRegistry.AuthenticatedSession session
                : sessionRegistry.sessionsFor(recipientId)) {
            try {
                publisher.publishToSession(
                        recipientId,
                        requiredRole,
                        threadId,
                        session
                );
            } catch (MessagingException exception) {
                LOGGER.warn(
                        "Rental presence delivery failed; errorType={}",
                        exception.getClass().getSimpleName()
                );
            }
        }
    }
}
