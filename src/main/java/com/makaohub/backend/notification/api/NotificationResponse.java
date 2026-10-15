package com.makaohub.backend.notification.api;

import com.makaohub.backend.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(

        UUID id,

        NotificationType type,

        String title,

        String body,

        boolean read,

        Instant createdAt
) {
}