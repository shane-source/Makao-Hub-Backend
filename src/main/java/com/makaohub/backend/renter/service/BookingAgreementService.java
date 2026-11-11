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
import com.makaohub.backend.notification.service.NotificationService;
import com.makaohub.backend.notification.domain.NotificationType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
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
    private final NotificationService notifications;

    public BookingAgreementService(
            BookingRepository bookingRepository,
            PaymentIntentRepository paymentIntentRepository,
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleVerificationRepository verificationRepository,
            MockEscrowLedgerService escrowLedgerService,
            MockPaymentAuditService auditService,
            NotificationService notifications
    ) {
        this.bookingRepository = bookingRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.verificationRepository = verificationRepository;
        this.escrowLedgerService = escrowLedgerService;
        this.auditService = auditService;
        this.notifications = notifications;
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
    public Booking confirmOwner(UUID ownerId, UUID bookingId, RoleName role) {
        if (role != RoleName.LANDLORD && role != RoleName.SUBLESSOR) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        requireActiveRole(ownerId, role);
        Booking booking = bookingRepository.findByIdAndOwnerIdForUpdate(bookingId, ownerId)
                .orElseThrow(() -> notFound("Booking not found."));
        RoleName ownerRole = booking.getListingKind() == BookingListingKind.LANDLORD_LISTING
                ? RoleName.LANDLORD
                : RoleName.SUBLESSOR;
        if (role != ownerRole) throw notFound("Booking not found.");
        requireVerified(ownerId, ownerRole);
        PaymentIntent intent = successfulBookingPayment(bookingId);
        booking.confirmOwnerAgreement(Instant.now());
        bookingRepository.save(booking);
        auditService.recordOwnerAgreementConfirmed(intent, booking);
        return booking;
    }

    @Transactional
    public Booking confirmMoveIn(UUID renterId, UUID bookingId) {
        throw new ResponseStatusException(HttpStatus.GONE, "Respond True or False to the owner's move-in report using the updated app.");
    }

    /** Owner initiates; no payout deadline starts until the renter answers True. */
    @Transactional
    public Booking confirmOwnerMoveIn(UUID ownerId, UUID bookingId, RoleName role, String idempotencyKey) {
        normalizeKey(idempotencyKey);
        if (role != RoleName.LANDLORD && role != RoleName.SUBLESSOR) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        requireActiveRole(ownerId, role);
        Booking booking = bookingRepository.findByIdAndOwnerIdForUpdate(bookingId, ownerId)
                .orElseThrow(() -> notFound("Booking not found."));
        RoleName bookingRole = booking.getListingKind() == BookingListingKind.LANDLORD_LISTING
                ? RoleName.LANDLORD : RoleName.SUBLESSOR;
        if (role != bookingRole) throw notFound("Booking not found.");
        requireVerified(ownerId, role);
        PaymentIntent intent = successfulBookingPayment(bookingId);
        try {
            if (booking.reportOwnerMoveIn(Instant.now())) {
                bookingRepository.save(booking);
                auditService.recordOwnerMoveInConfirmed(intent, booking);
            }
            String ownerName = userRepository.findById(ownerId).orElseThrow().getFullName();
            notifications.bookingNotice(booking.getRenterId(), bookingId, NotificationType.MOVE_IN_REPORTED,
                    "Did you move in?", ownerName + " reported that you moved in. Select True only if this is correct; False keeps payment held for staff review.");
        } catch (IllegalStateException | IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage());
        }
        return booking;
    }

    @Transactional
    public Booking respondToMoveIn(UUID renterId, UUID bookingId, boolean accepted, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        requireActiveRole(renterId, RoleName.RENTER);
        Booking booking = bookingRepository.findByIdAndRenterIdForUpdate(bookingId, renterId)
                .orElseThrow(() -> notFound("Booking not found."));
        PaymentIntent intent = successfulBookingPayment(bookingId);
        try {
            if (booking.respondToOwnerMoveIn(accepted, Instant.now(), key)) {
                bookingRepository.save(booking);
                auditService.recordMoveInResponse(intent, booking, accepted);
                notifications.bookingNotice(booking.getOwnerId(), bookingId, NotificationType.MOVE_IN_RESPONSE,
                        accepted ? "Renter answered True" : "Move-in needs staff review",
                        accepted ? "The renter accepted your report. The five-minute TEST payout wait has started."
                                : "The renter answered False. Payment remains held and requires staff review.");
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage());
        }
        return booking;
    }

    /** Shows only bookings owned by the account in its active owner role. */
    @Transactional(readOnly = true)
    public List<Booking> ownerBookings(UUID ownerId, RoleName role) {
        if (role != RoleName.LANDLORD && role != RoleName.SUBLESSOR) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        requireActiveRole(ownerId, role);
        return bookingRepository.findTop50ByOwnerIdAndListingKindOrderByCreatedAtDesc(
                ownerId, role == RoleName.LANDLORD ? BookingListingKind.LANDLORD_LISTING : BookingListingKind.SUBLESSOR_LISTING
        );
    }

    /** An owner signal to staff; it never shortens the five-minute wait after both confirmations or moves funds. */
    @Transactional
    public Booking requestOwnerReleaseReview(UUID ownerId, UUID bookingId, RoleName role, String idempotencyKey) {
        normalizeKey(idempotencyKey);
        if (role != RoleName.LANDLORD && role != RoleName.SUBLESSOR) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        requireActiveRole(ownerId, role);
        Booking booking = bookingRepository.findByIdAndOwnerIdForUpdate(bookingId, ownerId)
                .orElseThrow(() -> notFound("Booking not found."));
        RoleName bookingRole = booking.getListingKind() == BookingListingKind.LANDLORD_LISTING
                ? RoleName.LANDLORD : RoleName.SUBLESSOR;
        if (role != bookingRole) throw notFound("Booking not found.");
        requireVerified(ownerId, role);
        PaymentIntent intent = successfulBookingPayment(bookingId);
        try {
            if (booking.requestReleaseReview(Instant.now())) {
                bookingRepository.save(booking);
                auditService.recordOwnerReleaseReviewRequested(intent, booking);
            }
        } catch (IllegalStateException | IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage());
        }
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
        RoleName ownerRole = booking.getListingKind() == BookingListingKind.LANDLORD_LISTING
                ? RoleName.LANDLORD
                : RoleName.SUBLESSOR;
        requireActiveRole(booking.getOwnerId(), ownerRole);
        requireVerified(booking.getOwnerId(), ownerRole);
        if (!booking.isReleaseEligible(releaseTime)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The owner must report move-in and the renter must answer True, then wait five minutes."
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
        if (paymentIntentRepository.existsByBookingIdAndPaymentTypeAndStatus(
                bookingId, PaymentType.BOOKING, PaymentStatus.DISPUTED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking payment is disputed.");
        }
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
