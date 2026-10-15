package com.makaohub.backend.payment.daraja.repository;

import com.makaohub.backend.payment.daraja.domain.DarajaCallbackReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DarajaCallbackReceiptRepository
        extends JpaRepository<DarajaCallbackReceipt, UUID> {

    Optional<DarajaCallbackReceipt> findByCheckoutRequestId(
            String checkoutRequestId
    );

    /** Atomically ignores a replay or concurrent duplicate callback. */
    @Modifying
    @Query(value = """
            INSERT INTO daraja_callback_receipts
                (id, checkout_request_id, merchant_request_id, result_code,
                 result_description, mpesa_receipt_number, amount_minor, received_at)
            VALUES
                (:id, :checkoutRequestId, :merchantRequestId, :resultCode,
                 :resultDescription, :mpesaReceiptNumber, :amountMinor, :receivedAt)
            ON CONFLICT (checkout_request_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("checkoutRequestId") String checkoutRequestId,
            @Param("merchantRequestId") String merchantRequestId,
            @Param("resultCode") int resultCode,
            @Param("resultDescription") String resultDescription,
            @Param("mpesaReceiptNumber") String mpesaReceiptNumber,
            @Param("amountMinor") Long amountMinor,
            @Param("receivedAt") Instant receivedAt
    );
}
