package com.makaohub.backend.payment.audit.repository;

import com.makaohub.backend.payment.audit.domain.PaymentAuditEvent;
import com.makaohub.backend.payment.audit.domain.PaymentAuditEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentAuditEventRepository
        extends JpaRepository<PaymentAuditEvent, UUID> {

    boolean existsByPaymentIntentIdAndEventType(
            UUID paymentIntentId,
            PaymentAuditEventType eventType
    );
}
