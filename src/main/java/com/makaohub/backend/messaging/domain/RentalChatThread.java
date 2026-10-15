package com.makaohub.backend.messaging.domain;

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
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a private conversation between a Renter and a Landlord.
 *
 * One rental application creates at most one landlord conversation.
 */
@Entity
@Table(name = "rental_chat_threads")
public class RentalChatThread {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /*
     * The application that created this conversation.
     *
     * The database will enforce one thread per application.
     */
    @Column(
            name = "rental_application_id",
            nullable = false,
            unique = true,
            updatable = false
    )
    private UUID rentalApplicationId;

    @Column(
            name = "renter_id",
            nullable = false,
            updatable = false
    )
    private UUID renterId;

    @Column(
            name = "landlord_id",
            nullable = false,
            updatable = false
    )
    private UUID landlordId;

    /*
     * Landlord chat is available without a chat payment.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 20
    )
    private ChatThreadStatus status =
            ChatThreadStatus.OPEN;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @UpdateTimestamp
    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Required by JPA.
     */
    protected RentalChatThread() {
    }

    /**
     * Creates an open Renter-Landlord conversation.
     *
     * The backend creates this entity only after verifying the rental
     * application and listing ownership.
     */
    public RentalChatThread(
            UUID rentalApplicationId,
            UUID renterId,
            UUID landlordId
    ) {
        this.rentalApplicationId =
                Objects.requireNonNull(
                        rentalApplicationId,
                        "Rental application ID is required."
                );

        this.renterId =
                Objects.requireNonNull(
                        renterId,
                        "Renter ID is required."
                );

        this.landlordId =
                Objects.requireNonNull(
                        landlordId,
                        "Landlord ID is required."
                );

        if (renterId.equals(landlordId)) {
            throw new IllegalArgumentException(
                    "A renter and landlord must be different users."
            );
        }
    }

    /**
     * Closes the conversation.
     *
     * Closed conversations cannot receive new messages or expose
     * presence information.
     */
    public void close(
            Instant closedAt
    ) {
        if (status != ChatThreadStatus.OPEN) {
            throw new IllegalStateException(
                    "Only open chat threads can be closed."
            );
        }

        this.status = ChatThreadStatus.CLOSED;
        this.closedAt = Objects.requireNonNull(
                closedAt,
                "Chat closure time is required."
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getRentalApplicationId() {
        return rentalApplicationId;
    }

    public UUID getRenterId() {
        return renterId;
    }

    public UUID getLandlordId() {
        return landlordId;
    }

    public ChatThreadStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}