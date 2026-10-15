package com.makaohub.backend.property.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "listing_media")
public class ListingMedia {

    private static final long MAX_SIZE_BYTES = 52_428_800L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 10)
    private ListingMediaType mediaType;

    @Column(name = "object_path", nullable = false, length = 500)
    private String objectPath;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ListingMedia() {
    }

    public ListingMedia(
            UUID listingId,
            ListingMediaType mediaType,
            String objectPath,
            String contentType,
            long sizeBytes,
            short sortOrder
    ) {
        this.listingId = Objects.requireNonNull(listingId);
        this.mediaType = Objects.requireNonNull(mediaType);
        this.objectPath = Objects.requireNonNull(objectPath).strip();
        this.contentType = Objects.requireNonNull(contentType)
                .strip()
                .toLowerCase(Locale.ROOT);

        if (this.objectPath.isBlank()) {
            throw new IllegalArgumentException("Object path is required.");
        }

        if (sizeBytes <= 0 || sizeBytes > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "Media size is outside the allowed limit."
            );
        }

        if (sortOrder < 0 || sortOrder > 30) {
            throw new IllegalArgumentException(
                    "Media sort order is outside the allowed range."
            );
        }

        this.sizeBytes = sizeBytes;
        this.sortOrder = sortOrder;
    }

    public void confirm() {
        if (confirmedAt == null) {
            confirmedAt = Instant.now();
        }
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getListingId() {
        return listingId;
    }

    public ListingMediaType getMediaType() {
        return mediaType;
    }

    public String getObjectPath() {
        return objectPath;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public short getSortOrder() {
        return sortOrder;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}