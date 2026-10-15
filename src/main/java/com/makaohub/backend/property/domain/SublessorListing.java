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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "sublessor_listings")
public class SublessorListing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "sublessor_id", nullable = false)
    private UUID sublessorId;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 3000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "space_type", nullable = false, length = 30)
    private SublessorSpaceType spaceType;

    @Column(name = "monthly_rent_kes", nullable = false)
    private long monthlyRentKes;

    @Column(name = "bathroom_count", nullable = false)
    private short bathroomCount;

    @Column(nullable = false)
    private boolean furnished;

    @Column(name = "available_from")
    private LocalDate availableFrom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ListingStatus status = ListingStatus.DRAFT;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SublessorListing() {
    }

    public SublessorListing(
            UUID sublessorId,
            String title,
            String description,
            SublessorSpaceType spaceType,
            long monthlyRentKes,
            short bathroomCount,
            boolean furnished,
            LocalDate availableFrom
    ) {
        this.sublessorId = Objects.requireNonNull(sublessorId);

        updateDetails(
                title,
                description,
                spaceType,
                monthlyRentKes,
                bathroomCount,
                furnished,
                availableFrom
        );
    }

    public void updateDetails(
            String title,
            String description,
            SublessorSpaceType spaceType,
            long monthlyRentKes,
            short bathroomCount,
            boolean furnished,
            LocalDate availableFrom
    ) {
        this.title = Objects.requireNonNull(title)
                .strip();

        this.description = Objects.requireNonNull(description)
                .strip();

        this.spaceType = Objects.requireNonNull(spaceType);
        this.monthlyRentKes = monthlyRentKes;
        this.bathroomCount = bathroomCount;
        this.furnished = furnished;
        this.availableFrom = availableFrom;
    }

    public void publish() {
        if (status != ListingStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only draft listings can be published."
            );
        }

        status = ListingStatus.PUBLISHED;
    }

    public void pause() {
        if (status != ListingStatus.PUBLISHED) {
            throw new IllegalStateException(
                    "Only published listings can be paused."
            );
        }

        status = ListingStatus.PAUSED;
    }

    public void resume() {
        if (status != ListingStatus.PAUSED) {
            throw new IllegalStateException(
                    "Only paused listings can be resumed."
            );
        }

        status = ListingStatus.PUBLISHED;
    }

    public void archive() {
        if (status == ListingStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Listing is already archived."
            );
        }

        status = ListingStatus.ARCHIVED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSublessorId() {
        return sublessorId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public SublessorSpaceType getSpaceType() {
        return spaceType;
    }

    public long getMonthlyRentKes() {
        return monthlyRentKes;
    }

    public short getBathroomCount() {
        return bathroomCount;
    }

    public boolean isFurnished() {
        return furnished;
    }

    public LocalDate getAvailableFrom() {
        return availableFrom;
    }

    public ListingStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}