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

@Entity
@Table(name = "roommate_chat_threads")
public class RoommateChatThread {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /*
     * The accepted roommate interest that created this conversation.
     *
     * The database unique constraint ensures that one interest cannot
     * create multiple chat threads.
     */
    @Column(
            name = "roommate_interest_id",
            nullable = false,
            unique = true,
            updatable = false
    )
    private UUID roommateInterestId;

    @Column(
            name = "renter_id",
            nullable = false,
            updatable = false
    )
    private UUID renterId;

    @Column(
            name = "sublessor_id",
            nullable = false,
            updatable = false
    )
    private UUID sublessorId;

    /*
     * Roommate chat is free.
     *
     * A thread therefore starts OPEN immediately after the Sublessor
     * accepts the Renter's roommate interest.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoommateChatThreadStatus status =
            RoommateChatThreadStatus.OPEN;

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
    protected RoommateChatThread() {
    }

    /**
     * Creates an immediately available roommate conversation.
     *
     * The participant identifiers are supplied by trusted backend
     * services after validating the accepted roommate interest and
     * listing ownership. They are not accepted directly from a client.
     */
    public RoommateChatThread(
            UUID roommateInterestId,
            UUID renterId,
            UUID sublessorId
    ) {
        this.roommateInterestId =
                Objects.requireNonNull(
                        roommateInterestId,
                        "Roommate interest ID is required."
                );

        this.renterId =
                Objects.requireNonNull(
                        renterId,
                        "Renter ID is required."
                );

        this.sublessorId =
                Objects.requireNonNull(
                        sublessorId,
                        "Sublessor ID is required."
                );

        if (renterId.equals(sublessorId)) {
            throw new IllegalArgumentException(
                    "A renter and sublessor must be different users."
            );
        }
    }

    /**
     * Closes an open conversation.
     *
     * Closed threads cannot receive new messages or expose presence.
     */
    public void close(
            Instant closedAt
    ) {
        requireStatus(RoommateChatThreadStatus.OPEN);

        this.status = RoommateChatThreadStatus.CLOSED;
        this.closedAt = Objects.requireNonNull(
                closedAt,
                "Chat closure time is required."
        );
    }

    /**
     * Enforces valid thread-state transitions.
     */
    private void requireStatus(
            RoommateChatThreadStatus expectedStatus
    ) {
        if (status != expectedStatus) {
            throw new IllegalStateException(
                    "Invalid chat thread status transition."
            );
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getRoommateInterestId() {
        return roommateInterestId;
    }

    public UUID getRenterId() {
        return renterId;
    }

    public UUID getSublessorId() {
        return sublessorId;
    }

    public RoommateChatThreadStatus getStatus() {
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