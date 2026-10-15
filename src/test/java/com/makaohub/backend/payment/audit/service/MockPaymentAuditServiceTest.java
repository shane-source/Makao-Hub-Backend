package com.makaohub.backend.payment.audit.service;

import com.makaohub.backend.payment.audit.domain.PaymentAuditEvent;
import com.makaohub.backend.payment.audit.domain.PaymentAuditEventType;
import com.makaohub.backend.payment.audit.repository.PaymentAuditEventRepository;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MockPaymentAuditServiceTest {

    @Mock private PaymentAuditEventRepository repository;

    @Test
    void recordsEachLifecycleEventAtMostOnce() {
        UUID intentId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();
        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(intentId);
        when(intent.getPayerUserId()).thenReturn(payerId);
        when(intent.getPaymentType()).thenReturn(PaymentType.PLAN_UPGRADE);
        when(intent.getAmountMinor()).thenReturn(499L);
        when(intent.getCurrency()).thenReturn("KES");
        when(repository.existsByPaymentIntentIdAndEventType(
                intentId, PaymentAuditEventType.INTENT_CREATED
        )).thenReturn(false, true);

        MockPaymentAuditService service = new MockPaymentAuditService(repository);
        service.recordIntentCreated(intent);
        service.recordIntentCreated(intent);

        ArgumentCaptor<PaymentAuditEvent> captor =
                ArgumentCaptor.forClass(PaymentAuditEvent.class);
        verify(repository, times(1)).save(captor.capture());
        assertEquals(intentId, captor.getValue().getPaymentIntentId());
        assertEquals(PaymentAuditEventType.INTENT_CREATED,
                captor.getValue().getEventType());
    }
}
