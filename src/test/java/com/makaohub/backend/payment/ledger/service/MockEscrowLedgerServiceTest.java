package com.makaohub.backend.payment.ledger.service;

import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.ledger.domain.LedgerEntry;
import com.makaohub.backend.payment.ledger.domain.LedgerAccountType;
import com.makaohub.backend.payment.ledger.domain.LedgerSide;
import com.makaohub.backend.payment.ledger.repository.LedgerEntryRepository;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.BookingListingKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MockEscrowLedgerServiceTest {

    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private MockPaymentAuditService auditService;

    @Test
    void recordsBalancedRentAndDepositLinesOnce() {
        UUID intentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking booking = new Booking(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BookingListingKind.LANDLORD_LISTING, UUID.randomUUID(),
                100_000, 25_000, Booking.KES
        );
        setId(booking, bookingId);
        booking.beginPayment();
        booking.markPaidHeld();

        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(intentId);
        when(intent.getBookingId()).thenReturn(bookingId);
        when(intent.getPaymentType()).thenReturn(PaymentType.BOOKING);
        when(intent.getStatus()).thenReturn(PaymentStatus.SUCCEEDED);
        when(intent.getAmountMinor()).thenReturn(125_000L);
        when(ledgerEntryRepository.findAllByPaymentIntentId(intentId))
                .thenReturn(List.of());
        when(ledgerEntryRepository.saveAll(any())).thenAnswer(invocation ->
                invocation.getArgument(0));

        MockEscrowLedgerService service = new MockEscrowLedgerService(
                ledgerEntryRepository, auditService
        );
        service.recordBookingHold(intent, booking);

        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(captor.capture());
        List<LedgerEntry> lines = captor.getValue();
        assertEquals(4, lines.size());
        assertEquals(125_000, total(lines, LedgerSide.DEBIT));
        assertEquals(125_000, total(lines, LedgerSide.CREDIT));
    }

    @Test
    void recordsOwnerPayableLinesForRentAndDepositAfterRelease() {
        UUID intentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking booking = new Booking(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BookingListingKind.SUBLESSOR_LISTING, UUID.randomUUID(),
                100_000, 25_000, Booking.KES
        );
        setId(booking, bookingId);
        booking.beginPayment();
        booking.markPaidHeld();
        booking.confirmRenterAgreement(java.time.Instant.EPOCH);
        booking.confirmOwnerAgreement(java.time.Instant.EPOCH);
        booking.confirmMoveIn(java.time.Instant.EPOCH);
        booking.markReleased(
                java.time.Instant.EPOCH.plus(24, java.time.temporal.ChronoUnit.HOURS),
                "mock-payout-1",
                "release-1"
        );

        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(intentId);
        when(intent.getBookingId()).thenReturn(bookingId);
        when(intent.getPaymentType()).thenReturn(PaymentType.BOOKING);
        when(intent.getStatus()).thenReturn(PaymentStatus.SUCCEEDED);
        when(ledgerEntryRepository.findAllByBookingId(bookingId)).thenReturn(List.of());
        when(ledgerEntryRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MockEscrowLedgerService service = new MockEscrowLedgerService(
                ledgerEntryRepository, auditService
        );
        service.recordBookingRelease(intent, booking, UUID.randomUUID());

        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(captor.capture());
        List<LedgerEntry> lines = captor.getValue();
        assertEquals(4, lines.size());
        assertEquals(2, lines.stream()
                .filter(line -> line.getAccountType() == LedgerAccountType.OWNER_PAYABLE)
                .count());
        assertEquals(125_000, total(lines, LedgerSide.DEBIT));
        assertEquals(125_000, total(lines, LedgerSide.CREDIT));
    }

    private static long total(List<LedgerEntry> lines, LedgerSide side) {
        return lines.stream()
                .filter(line -> line.getSide() == side)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();
    }

    private static void setId(Object entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
