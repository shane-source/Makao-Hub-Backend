package com.makaohub.backend.payment.paystack.repository;

import com.makaohub.backend.payment.paystack.domain.PaystackWebhookReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface PaystackWebhookReceiptRepository
        extends JpaRepository<PaystackWebhookReceipt, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO paystack_webhook_receipts
                (id, event_key, event_type, provider_reference, received_at)
            VALUES
                (:id, :eventKey, :eventType, :providerReference, :receivedAt)
            ON CONFLICT (event_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("eventKey") String eventKey,
            @Param("eventType") String eventType,
            @Param("providerReference") String providerReference,
            @Param("receivedAt") Instant receivedAt
    );
}
