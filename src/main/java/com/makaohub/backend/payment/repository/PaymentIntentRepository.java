package com.makaohub.backend.payment.repository;

import com.makaohub.backend.payment.domain.PaymentIntent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository
        extends JpaRepository<PaymentIntent, UUID> {

    Optional<PaymentIntent> findByPayerUserIdAndIdempotencyKey(
            UUID payerUserId,
            String idempotencyKey
    );

    Optional<PaymentIntent> findByProviderReference(
            String providerReference
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT intent FROM PaymentIntent intent
            WHERE intent.providerReference = :providerReference
            """)
    Optional<PaymentIntent> findByProviderReferenceForUpdate(
            @Param("providerReference") String providerReference
    );

    Optional<PaymentIntent> findByPayerUserIdAndProviderReference(
            UUID payerUserId,
            String providerReference
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT intent FROM PaymentIntent intent
            WHERE intent.payerUserId = :payerUserId
              AND intent.providerReference = :providerReference
            """)
    Optional<PaymentIntent> findByPayerUserIdAndProviderReferenceForUpdate(
            @Param("payerUserId") UUID payerUserId,
            @Param("providerReference") String providerReference
    );

    Optional<PaymentIntent> findByProviderReferenceAndPayerUserId(
            String providerReference,
            UUID payerUserId
    );

    Optional<PaymentIntent> findByIdAndPayerUserId(
            UUID id,
            UUID payerUserId
    );

    Page<PaymentIntent> findAllByPayerUserId(
            UUID payerUserId,
            Pageable pageable
    );

    List<PaymentIntent> findAllByPayerUserIdAndBookingId(
            UUID payerUserId,
            UUID bookingId
    );

    Optional<PaymentIntent> findFirstByBookingIdAndPaymentTypeAndStatusOrderByCreatedAtDesc(
            UUID bookingId,
            com.makaohub.backend.payment.domain.PaymentType paymentType,
            com.makaohub.backend.payment.domain.PaymentStatus status
    );
}
