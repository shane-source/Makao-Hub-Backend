package com.makaohub.backend.payment.paystack.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Deduplication record for a verified Paystack webhook event. */
@Entity
@Table(name = "paystack_webhook_receipts")
public class PaystackWebhookReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_key", nullable = false, unique = true, length = 220)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "provider_reference", nullable = false, length = 120)
    private String providerReference;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PaystackWebhookReceipt() {
    }

    public PaystackWebhookReceipt(
            String eventKey,
            String eventType,
            String providerReference,
            Instant receivedAt
    ) {
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.providerReference = providerReference;
        this.receivedAt = receivedAt;
    }
}
