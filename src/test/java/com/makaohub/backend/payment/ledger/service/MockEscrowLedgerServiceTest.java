package com.makaohub.backend.payment.ledger.service;

import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.ledger.domain.LedgerEntry;
import com.makaohub.backend.payment.ledger.domain.LedgerAccountType;
import com.makaohub.backend.payment.ledger.domain.LedgerSide;
import com.makaohub.backend.payment.ledger.domain.LedgerComponent;
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
import static org.mockito.Mockito.reset;

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
        booking.confirmOwnerMoveIn(java.time.Instant.EPOCH);
        booking.respondToOwnerMoveIn(true, java.time.Instant.EPOCH, "fresh-test-response");
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

    @Test
    void holdsFullBookingTotalIncludingCommissionUntilRelease() {
        UUID intentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking booking = new Booking(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BookingListingKind.LANDLORD_LISTING, UUID.randomUUID(),
                4_000_000, 4_000_000, 400_000, Booking.KES
        );
        setId(booking, bookingId);
        booking.beginPayment();
        booking.markPaidHeld();

        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(intentId);
        when(intent.getBookingId()).thenReturn(bookingId);
        when(intent.getPaymentType()).thenReturn(PaymentType.BOOKING);
        when(intent.getStatus()).thenReturn(PaymentStatus.SUCCEEDED);
        when(intent.getAmountMinor()).thenReturn(8_400_000L);
        when(ledgerEntryRepository.findAllByPaymentIntentId(intentId))
                .thenReturn(List.of());
        when(ledgerEntryRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MockEscrowLedgerService service = new MockEscrowLedgerService(
                ledgerEntryRepository, auditService
        );
        service.recordBookingHold(intent, booking);

        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(captor.capture());
        List<LedgerEntry> holdLines = captor.getValue();
        assertEquals(6, holdLines.size());
        assertEquals(8_400_000L, total(holdLines, LedgerSide.DEBIT));
        assertEquals(8_400_000L, total(holdLines, LedgerSide.CREDIT));
        assertEquals(8_400_000L, creditTo(holdLines, LedgerAccountType.ESCROW_HOLD));
        assertEquals(0, creditTo(holdLines, LedgerAccountType.PLATFORM_REVENUE));

        booking.confirmRenterAgreement(java.time.Instant.EPOCH);
        booking.confirmOwnerAgreement(java.time.Instant.EPOCH);
        booking.confirmMoveIn(java.time.Instant.EPOCH);
        booking.confirmOwnerMoveIn(java.time.Instant.EPOCH);
        booking.respondToOwnerMoveIn(true, java.time.Instant.EPOCH, "fresh-test-response");
        booking.markReleased(
                java.time.Instant.EPOCH.plus(24, java.time.temporal.ChronoUnit.HOURS),
                "mock-payout-84k", "release-84k"
        );
        reset(ledgerEntryRepository);
        when(ledgerEntryRepository.findAllByBookingId(bookingId)).thenReturn(holdLines);
        when(ledgerEntryRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.recordBookingRelease(intent, booking, UUID.randomUUID());

        verify(ledgerEntryRepository).saveAll(captor.capture());
        List<LedgerEntry> releaseLines = captor.getValue();
        assertEquals(6, releaseLines.size());
        assertEquals(8_400_000L, total(releaseLines, LedgerSide.DEBIT));
        assertEquals(8_400_000L, total(releaseLines, LedgerSide.CREDIT));
        assertEquals(8_000_000L, creditTo(releaseLines, LedgerAccountType.OWNER_PAYABLE));
        assertEquals(400_000L, creditTo(releaseLines, LedgerAccountType.PLATFORM_REVENUE));
        assertEquals(400_000L, releaseLines.stream()
                .filter(line -> line.getComponent() == LedgerComponent.COMMISSION
                        && line.getAccountType() == LedgerAccountType.ESCROW_HOLD
                        && line.getSide() == LedgerSide.DEBIT)
                .mapToLong(LedgerEntry::getAmountMinor).sum());
    }

    @Test
    void releasesLegacyBookingWithoutRecognizingCommissionTwice() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = new Booking(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BookingListingKind.LANDLORD_LISTING, UUID.randomUUID(),
                4_000_000, 4_000_000, 400_000, Booking.KES
        );
        setId(booking, bookingId);
        booking.beginPayment();
        booking.markPaidHeld();
        booking.confirmRenterAgreement(java.time.Instant.EPOCH);
        booking.confirmOwnerAgreement(java.time.Instant.EPOCH);
        booking.confirmMoveIn(java.time.Instant.EPOCH);
        booking.confirmOwnerMoveIn(java.time.Instant.EPOCH);
        booking.respondToOwnerMoveIn(true, java.time.Instant.EPOCH, "fresh-test-response");
        booking.markReleased(
                java.time.Instant.EPOCH.plus(24, java.time.temporal.ChronoUnit.HOURS),
                "mock-payout-legacy", "release-legacy"
        );

        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(UUID.randomUUID());
        when(intent.getBookingId()).thenReturn(bookingId);
        when(intent.getPaymentType()).thenReturn(PaymentType.BOOKING);
        when(intent.getStatus()).thenReturn(PaymentStatus.SUCCEEDED);
        LedgerEntry legacyCommission = ledgerLine(intent, booking,
                LedgerAccountType.PLATFORM_REVENUE,
                LedgerComponent.COMMISSION, LedgerSide.CREDIT, 400_000);
        when(ledgerEntryRepository.findAllByBookingId(bookingId))
                .thenReturn(List.of(legacyCommission));
        when(ledgerEntryRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        new MockEscrowLedgerService(ledgerEntryRepository, auditService)
                .recordBookingRelease(intent, booking, UUID.randomUUID());

        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(captor.capture());
        assertEquals(4, captor.getValue().size());
        assertEquals(0, creditTo(captor.getValue(), LedgerAccountType.PLATFORM_REVENUE));
    }

    private static LedgerEntry ledgerLine(PaymentIntent intent, Booking booking,
            LedgerAccountType account, LedgerComponent component, LedgerSide side, long amount) {
        return new LedgerEntry(UUID.randomUUID(), intent.getId(), booking.getId(),
                account, component, side, amount, Booking.KES);
    }

    private static long creditTo(List<LedgerEntry> lines, LedgerAccountType account) {
        return lines.stream()
                .filter(line -> line.getSide() == LedgerSide.CREDIT
                        && line.getAccountType() == account)
                .mapToLong(LedgerEntry::getAmountMinor).sum();
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
