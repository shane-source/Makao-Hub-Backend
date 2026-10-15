package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.ledger.service.MockEscrowLedgerService;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.BookingListingKind;
import com.makaohub.backend.renter.domain.BookingStatus;
import com.makaohub.backend.renter.repository.BookingRepository;
import com.makaohub.backend.verification.domain.RoleVerification;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import com.makaohub.backend.auth.domain.RoleName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingAgreementServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private PaymentIntentRepository paymentIntentRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleVerificationRepository verificationRepository;
    @Mock private MockEscrowLedgerService ledgerService;
    @Mock private MockPaymentAuditService auditService;

    @Test
    void renterConfirmationIsOwnerScopedAndAudited() {
        UUID renterId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking booking = paidBooking(renterId, UUID.randomUUID(), bookingId);
        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        User user = activeUser();

        when(userRepository.findById(renterId)).thenReturn(Optional.of(user));
        when(userRoleRepository.existsByUser_IdAndRole_Name(any(), any()))
                .thenReturn(true);
        when(bookingRepository.findByIdAndRenterIdForUpdate(bookingId, renterId))
                .thenReturn(Optional.of(booking));
        when(paymentIntentRepository
                .findFirstByBookingIdAndPaymentTypeAndStatusOrderByCreatedAtDesc(
                        bookingId, PaymentType.BOOKING, PaymentStatus.SUCCEEDED
                )).thenReturn(Optional.of(intent));

        Booking result = service().confirmRenter(renterId, bookingId);

        assertEquals(booking, result);
        assertFalse(booking.bothPartiesAgreed());
        verify(auditService).recordRenterAgreementConfirmed(intent, booking);
        verify(bookingRepository).save(booking);
    }

    @Test
    void adminReleaseIsIdempotentAfterBothPartiesAgree() {
        UUID renterId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking booking = paidBooking(renterId, ownerId, bookingId);
        booking.confirmRenterAgreement(java.time.Instant.EPOCH);
        booking.confirmOwnerAgreement(java.time.Instant.EPOCH);
        booking.confirmMoveIn(java.time.Instant.EPOCH);
        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        User admin = activeUser();

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        User owner = activeUser();
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(userRoleRepository.existsByUser_IdAndRole_Name(any(), any()))
                .thenReturn(true);
        RoleVerification ownerVerification = org.mockito.Mockito.mock(RoleVerification.class);
        when(verificationRepository.findByUserIdAndRole(ownerId, RoleName.LANDLORD))
                .thenReturn(Optional.of(ownerVerification));
        when(ownerVerification.getStatus()).thenReturn(VerificationStatus.VERIFIED);
        when(bookingRepository.findByIdForUpdate(bookingId))
                .thenReturn(Optional.of(booking));
        when(paymentIntentRepository
                .findFirstByBookingIdAndPaymentTypeAndStatusOrderByCreatedAtDesc(
                        bookingId, PaymentType.BOOKING, PaymentStatus.SUCCEEDED
                )).thenReturn(Optional.of(intent));

        Booking released = service().releaseMock(
                adminId,
                bookingId,
                "release-1",
                Instant.EPOCH.plus(24, ChronoUnit.HOURS)
        );
        Booking repeated = service().releaseMock(
                adminId,
                bookingId,
                "release-1",
                Instant.EPOCH.plus(24, ChronoUnit.HOURS)
        );

        assertEquals(BookingStatus.ACTIVE, released.getStatus());
        assertEquals(released, repeated);
        verify(ledgerService).recordBookingRelease(intent, booking, adminId);
    }

    @Test
    void releaseRequiresBothConfirmations() {
        UUID adminId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking booking = paidBooking(UUID.randomUUID(), UUID.randomUUID(), bookingId);
        User admin = activeUser();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(userRoleRepository.existsByUser_IdAndRole_Name(any(), any()))
                .thenReturn(true);
        when(bookingRepository.findByIdForUpdate(bookingId))
                .thenReturn(Optional.of(booking));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service().releaseMock(adminId, bookingId, "release-2")
        );
        verify(ledgerService, never()).recordBookingRelease(any(), any(), any());
    }

    @Test
    void releaseCannotHappenBeforeMoveInHoldingPeriod() {
        UUID renterId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking booking = paidBooking(renterId, ownerId, bookingId);
        booking.confirmRenterAgreement(Instant.EPOCH);
        booking.confirmOwnerAgreement(Instant.EPOCH);
        booking.confirmMoveIn(Instant.EPOCH);
        User admin = activeUser();

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(userRoleRepository.existsByUser_IdAndRole_Name(any(), any()))
                .thenReturn(true);
        when(bookingRepository.findByIdForUpdate(bookingId))
                .thenReturn(Optional.of(booking));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service().releaseMock(adminId, bookingId, "release-early", Instant.EPOCH)
        );
        verify(ledgerService, never()).recordBookingRelease(any(), any(), any());
    }

    @Test
    void releaseRechecksOwnerVerificationAtReleaseTime() {
        UUID renterId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking booking = paidBooking(renterId, ownerId, bookingId);
        booking.confirmRenterAgreement(Instant.EPOCH);
        booking.confirmOwnerAgreement(Instant.EPOCH);
        booking.confirmMoveIn(Instant.EPOCH);
        User admin = activeUser();
        User owner = activeUser();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(userRoleRepository.existsByUser_IdAndRole_Name(any(), any())).thenReturn(true);
        RoleVerification revoked = org.mockito.Mockito.mock(RoleVerification.class);
        when(verificationRepository.findByUserIdAndRole(ownerId, RoleName.LANDLORD))
                .thenReturn(Optional.of(revoked));
        when(revoked.getStatus()).thenReturn(VerificationStatus.REJECTED);
        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(booking));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service().releaseMock(
                        adminId, bookingId, "release-revoked", Instant.EPOCH.plus(24, ChronoUnit.HOURS)
                )
        );
        verify(ledgerService, never()).recordBookingRelease(any(), any(), any());
    }

    @Test
    void ownerConfirmationRequiresVerifiedOwnerRole() {
        UUID ownerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking booking = paidBooking(UUID.randomUUID(), ownerId, bookingId);
        User owner = activeUser();
        RoleVerification verification = org.mockito.Mockito.mock(RoleVerification.class);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(userRoleRepository.existsByUser_IdAndRole_Name(any(), any()))
                .thenReturn(true);
        when(bookingRepository.findByIdAndOwnerIdForUpdate(bookingId, ownerId))
                .thenReturn(Optional.of(booking));
        when(verificationRepository.findByUserIdAndRole(any(), any()))
                .thenReturn(Optional.of(verification));
        when(verification.getStatus()).thenReturn(VerificationStatus.PENDING);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service().confirmOwner(ownerId, bookingId)
        );
        verify(paymentIntentRepository, never())
                .findFirstByBookingIdAndPaymentTypeAndStatusOrderByCreatedAtDesc(
                        any(), any(), any()
                );
    }

    private BookingAgreementService service() {
        return new BookingAgreementService(
                bookingRepository,
                paymentIntentRepository,
                userRepository,
                userRoleRepository,
                verificationRepository,
                ledgerService,
                auditService
        );
    }

    private static Booking paidBooking(UUID renterId, UUID ownerId, UUID bookingId) {
        Booking booking = new Booking(
                renterId, ownerId, UUID.randomUUID(),
                BookingListingKind.LANDLORD_LISTING, UUID.randomUUID(),
                100_000, 20_000, Booking.KES
        );
        setId(booking, bookingId);
        booking.beginPayment();
        booking.markPaidHeld();
        return booking;
    }

    private static User activeUser() {
        User user = org.mockito.Mockito.mock(User.class);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        return user;
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
