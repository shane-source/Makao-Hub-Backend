package com.makaohub.backend.messaging.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.makaohub.backend.messaging.config.WebSocketSecurityProperties;
import io.github.bucket4j.Bucket;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

@Component
public class ChatMessageRateLimiter {

    private final long messagesPerMinute;

    private final Cache<UUID, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    public ChatMessageRateLimiter(
            WebSocketSecurityProperties properties
    ) {
        this.messagesPerMinute = properties.messagesPerMinute();
    }

    public void requirePermission(UUID userId) {
        Bucket bucket = buckets.get(
                userId,
                ignored -> newBucket()
        );

        if (!bucket.tryConsume(1)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many chat messages. Try again shortly."
            );
        }
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit
                        .capacity(messagesPerMinute)
                        .refillGreedy(
                                messagesPerMinute,
                                Duration.ofMinutes(1)
                        )
                )
                .build();
    }
}
