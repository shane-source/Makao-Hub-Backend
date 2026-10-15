package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Coordinates the two-party agreement and mock escrow release.
 *
 * This class records state only; the mock payout reference does not move
 * money. A real Paystack transfer adapter must be enabled only after the
 * regulated custody arrangement and payout-destination workflow are approved.
 */
@Service
public class BookingAgreementService {

    private final BookingRepository bookingRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleVerificationRepository verificationRepository;
    private final MockEscrowLedgerService escrowLedgerService;
    private final MockPaymentAuditService auditService;

    public BookingAgreementService(
            BookingRepository bookingRepository,
            PaymentIntentRepository paymentIntentRepository,
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleVerificationRepository verificationRepository,
            MockEscrowLedgerService escrowLedgerService,
            MockPaymentAuditService auditService
    ) {
        this.bookingRepository = bookingRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.verificationRepository = verificationRepository;
        this.escrowLedgerService = escrowLedgerService;
        this.auditService = auditService;
    }

    @Transactional
    public Booking confirmRenter(UUID renterId, UUID bookingId) {
        requireActiveRole(renterId, RoleName.RENTER);
        Booking booking = bookingRepository.findByIdAndRenterIdForUpdate(bookingId, renterId)
                .orElseThrow(() -> notFound("Booking not found."));
        PaymentIntent intent = successfulBookingPayment(bookingId);
        booking.confirmRenterAgreement(Instant.now());
        bookingRepository.save(booking);
        auditService.recordRenterAgreementConfirmed(intent, booking);
        return booking;
    }

    @Transactional
    public Booking confirmOwner(UUID ownerId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdAndOwnerIdForUpdate(bookingId, ownerId)
                .orElseThrow(() -> notFound("Booking not found."));
        RoleName ownerRole = booking.getListingKind() == BookingListingKind.LANDLORD_LISTING
                ? RoleName.LANDLORD
                : RoleName.SUBLESSOR;
        requireActiveRole(ownerId, ownerRole);
        requireVerified(ownerId, ownerRole);
        PaymentIntent intent = successfulBookingPayment(bookingId);
        booking.confirmOwnerAgreement(Instant.now());
        bookingRepository.save(booking);
        auditService.recordOwnerAgreementConfirmed(intent, booking);
        return booking;
    }

    @Transactional
    public Booking confirmMoveIn(UUID renterId, UUID bookingId) {
        requireActiveRole(renterId, RoleName.RENTER);
        Booking booking = bookingRepository.findByIdAndRenterIdForUpdate(bookingId, renterId)
                .orElseThrow(() -> notFound("Booking not found."));
        PaymentIntent intent = successfulBookingPayment(bookingId);
        booking.confirmMoveIn(Instant.now());
        bookingRepository.save(booking);
        auditService.recordMoveInConfirmed(intent, booking);
        return booking;
    }

    @Transactional
    public Booking releaseMock(UUID adminId, UUID bookingId, String idempotencyKey) {
        return releaseMock(adminId, bookingId, idempotencyKey, Instant.now());
    }

    @Transactional
    public Booking releaseMock(
            UUID adminId,
            UUID bookingId,
            String idempotencyKey,
            Instant now
    ) {
        requireActiveRole(adminId, RoleName.ADMIN);
        String key = normalizeKey(idempotencyKey);
        Instant releaseTime = now == null
                ? Instant.now()
                : now;
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> notFound("Booking not found."));
        if (booking.getStatus() == BookingStatus.ACTIVE) {
            if (key.equals(booking.getReleaseIdempotencyKey())) {
                return booking;
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This booking has already been released with another key."
            );
        }
        if (!booking.bothPartiesAgreed()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Both renter and owner must confirm before release."
            );
        }
        RoleName ownerRole = booking.getListingKind() == BookingListingKind.LANDLORD_LISTING
                ? RoleName.LANDLORD
                : RoleName.SUBLESSOR;
        requireActiveRole(booking.getOwnerId(), ownerRole);
        requireVerified(booking.getOwnerId(), ownerRole);
        if (!booking.isReleaseEligible(releaseTime)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Release is available 24 hours after the renter confirms move-in."
            );
        }
        PaymentIntent intent = successfulBookingPayment(bookingId);
        String payoutReference = "mock-payout-" + UUID.randomUUID();
        booking.markReleased(releaseTime, payoutReference, key);
        bookingRepository.save(booking);
        escrowLedgerService.recordBookingRelease(intent, booking, adminId);
        return booking;
    }

    private PaymentIntent successfulBookingPayment(UUID bookingId) {
        return paymentIntentRepository
                .findFirstByBookingIdAndPaymentTypeAndStatusOrderByCreatedAtDesc(
                        bookingId, PaymentType.BOOKING, PaymentStatus.SUCCEEDED
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A successful booking payment is required first."
                ));
    }

    private void requireVerified(UUID userId, RoleName role) {
        RoleVerification verification = verificationRepository
                .findByUserIdAndRole(userId, role)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "The owner role is not verified."
                ));
        if (verification.getStatus() != VerificationStatus.VERIFIED) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "The owner role is not verified."
            );
        }
    }

    private void requireActiveRole(UUID userId, RoleName role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Account is unavailable."
                ));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Account is not active."
            );
        }
        if (!userRoleRepository.existsByUser_IdAndRole_Name(userId, role)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "The account does not have the required role."
            );
        }
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain 1 to 100 characters."
            );
        }
        return key.strip();
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
