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
@Table(name = "listings")
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "landlord_id", nullable = false)
    private UUID landlordId;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 3000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false, length = 30)
    private PropertyType propertyType;

    @Column(name = "monthly_rent_kes", nullable = false)
    private long monthlyRentKes;

    @Column(name = "bedroom_count", nullable = false)
    private short bedroomCount;

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

    protected Listing() {
    }

    public Listing(
            UUID landlordId,
            String title,
            String description,
            PropertyType propertyType,
            long monthlyRentKes,
            short bedroomCount,
            short bathroomCount,
            boolean furnished,
            LocalDate availableFrom
    ) {
        this.landlordId = Objects.requireNonNull(landlordId);
        updateDetails(
                title,
                description,
                propertyType,
                monthlyRentKes,
                bedroomCount,
                bathroomCount,
                furnished,
                availableFrom
        );
    }

    public void updateDetails(
            String title,
            String description,
            PropertyType propertyType,
            long monthlyRentKes,
            short bedroomCount,
            short bathroomCount,
            boolean furnished,
            LocalDate availableFrom
    ) {
        this.title = Objects.requireNonNull(title).strip();
        this.description = Objects.requireNonNull(description).strip();
        this.propertyType = Objects.requireNonNull(propertyType);
        this.monthlyRentKes = monthlyRentKes;
        this.bedroomCount = bedroomCount;
        this.bathroomCount = bathroomCount;
        this.furnished = furnished;
        this.availableFrom = availableFrom;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLandlordId() {
        return landlordId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public PropertyType getPropertyType() {
        return propertyType;
    }

    public long getMonthlyRentKes() {
        return monthlyRentKes;
    }

    public short getBedroomCount() {
        return bedroomCount;
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