package com.makaohub.backend.renter.service;

import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.payment.service.MockPaymentOutcome;
import com.makaohub.backend.payment.service.PaymentIntentService;
import com.makaohub.backend.payment.ledger.service.MockEscrowLedgerService;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.BookingListingKind;
import com.makaohub.backend.renter.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingPaymentServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private PaymentIntentRepository paymentIntentRepository;
    @Mock private PaymentIntentService paymentIntentService;
    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private MockEscrowLedgerService escrowLedgerService;
    @Mock private MockPaymentAuditService auditService;

    @Test
    void startsOneServerPricedBookingPayment() {
        UUID renterId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        Booking booking = new Booking(
                renterId, ownerId, listingId, BookingListingKind.LANDLORD_LISTING,
                applicationId, 100_000, 20_000, Booking.KES
        );
        setId(booking, bookingId);
        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        User user = org.mockito.Mockito.mock(User.class);
        when(userRepository.findById(renterId)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(userRoleRepository.existsByUser_IdAndRole_Name(any(), any()))
                .thenReturn(true);
        when(bookingRepository.findByIdAndRenterId(bookingId, renterId))
                .thenReturn(Optional.of(booking));
        when(paymentIntentRepository.findByPayerUserIdAndIdempotencyKey(
                renterId, "booking-1"
        )).thenReturn(Optional.empty());
        when(paymentIntentRepository.findAllByPayerUserIdAndBookingId(
                renterId, bookingId
        )).thenReturn(java.util.List.of());
        when(paymentIntentService.create(any())).thenReturn(intent);

        PaymentIntent result = service().createPaymentIntent(
                renterId, bookingId, "booking-1"
        );

        assertEquals(intent, result);
        assertEquals(com.makaohub.backend.renter.domain.BookingStatus.PAYMENT_PENDING,
                booking.getStatus());
        ArgumentCaptor<PaymentIntentService.CreatePaymentCommand> captor =
                ArgumentCaptor.forClass(PaymentIntentService.CreatePaymentCommand.class);
        verify(paymentIntentService).create(captor.capture());
        assertEquals(bookingId, captor.getValue().bookingId());
        assertEquals(120_000, captor.getValue().amountMinor());
    }

    @Test
    void successfulMockPaymentMovesBookingToPaidHeld() {
        UUID renterId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking booking = new Booking(
                renterId, UUID.randomUUID(), UUID.randomUUID(),
                BookingListingKind.SUBLESSOR_LISTING, UUID.randomUUID(),
                80_000, 20_000, Booking.KES
        );
        setId(booking, bookingId);
        booking.beginPayment();
        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        User user = org.mockito.Mockito.mock(User.class);
        when(userRepository.findById(renterId)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(userRoleRepository.existsByUser_IdAndRole_Name(any(), any()))
                .thenReturn(true);
        when(paymentIntentService.completeMockCallback(
                renterId, "mock-1", MockPaymentOutcome.SUCCESS, Instant.EPOCH
        )).thenReturn(intent);
        when(intent.getPaymentType()).thenReturn(PaymentType.BOOKING);
        when(intent.getStatus()).thenReturn(PaymentStatus.SUCCEEDED);
        when(intent.getBookingId()).thenReturn(bookingId);
        when(bookingRepository.findByIdAndRenterId(bookingId, renterId))
                .thenReturn(Optional.of(booking));

        service().completeMockPayment(
                renterId, "mock-1", MockPaymentOutcome.SUCCESS, Instant.EPOCH
        );

        assertEquals(com.makaohub.backend.renter.domain.BookingStatus.PAID_HELD,
                booking.getStatus());
        verify(bookingRepository).save(booking);
    }

    private BookingPaymentService service() {
        return new BookingPaymentService(
                bookingRepository,
                paymentIntentRepository,
                paymentIntentService,
                userRepository,
                userRoleRepository,
                escrowLedgerService,
                auditService
        );
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
