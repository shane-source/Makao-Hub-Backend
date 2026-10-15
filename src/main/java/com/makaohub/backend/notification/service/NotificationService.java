package com.makaohub.backend.notification.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.notification.api.NotificationResponse;
import com.makaohub.backend.notification.domain.Notification;
import com.makaohub.backend.notification.domain.NotificationType;
import com.makaohub.backend.notification.repository.NotificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;

    public NotificationService(
            UserRepository userRepository,
            NotificationRepository notificationRepository
    ) {
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public void create(
            UUID userId,
            NotificationType type,
            String title,
            String body
    ) {
        requireActiveUser(userId);

        notificationRepository.save(
                new Notification(
                        userId,
                        type,
                        title,
                        body
                )
        );
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> findMine(
            UUID userId
    ) {
        requireActiveUser(userId);

        return notificationRepository
                .findAllByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void markRead(
            UUID userId,
            UUID notificationId
    ) {
        requireActiveUser(userId);

        Notification notification =
                notificationRepository.findById(notificationId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Notification not found."
                        ));

        if (!notification.getUserId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Notification not found."
            );
        }

        notification.markRead();
    }

    private NotificationResponse toResponse(
            Notification notification
    ) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getReadAt() != null,
                notification.getCreatedAt()
        );
    }

    private void requireActiveUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Account is unavailable."
                ));

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account is not active."
            );
        }
    }
}