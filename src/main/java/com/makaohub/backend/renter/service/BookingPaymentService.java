package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.payment.service.MockPaymentOutcome;
import com.makaohub.backend.payment.service.PaymentIntentService;
import com.makaohub.backend.payment.ledger.service.MockEscrowLedgerService;
import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.BookingStatus;
import com.makaohub.backend.renter.repository.BookingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/** Starts and settles the mock payment for one renter-owned booking. */
@Service
public class BookingPaymentService {

    private final BookingRepository bookingRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentIntentService paymentIntentService;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final MockEscrowLedgerService escrowLedgerService;
    private final MockPaymentAuditService auditService;

    public BookingPaymentService(
            BookingRepository bookingRepository,
            PaymentIntentRepository paymentIntentRepository,
            PaymentIntentService paymentIntentService,
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            MockEscrowLedgerService escrowLedgerService,
            MockPaymentAuditService auditService
    ) {
        this.bookingRepository = bookingRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentIntentService = paymentIntentService;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.escrowLedgerService = escrowLedgerService;
        this.auditService = auditService;
    }

    @Transactional
    public PaymentIntent createPaymentIntent(
            UUID renterId,
            UUID bookingId,
            String idempotencyKey
    ) {
        requireActiveRenter(renterId);
        Booking booking = bookingRepository.findByIdAndRenterId(bookingId, renterId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Booking not found."
                ));

        PaymentIntent existingForKey = paymentIntentRepository
                .findByPayerUserIdAndIdempotencyKey(renterId, idempotencyKey)
                .orElse(null);
        if (existingForKey != null
                && (!bookingId.equals(existingForKey.getBookingId())
                || existingForKey.getPaymentType() != PaymentType.BOOKING)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The idempotency key was already used for another payment."
            );
        }

        if (existingForKey == null) {
            if (!paymentIntentRepository
                    .findAllByPayerUserIdAndBookingId(renterId, bookingId)
                    .isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This booking already has a payment attempt."
                );
            }
            if (booking.getStatus() != BookingStatus.AWAITING_PAYMENT) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The booking is not awaiting payment."
                );
            }
            booking.beginPayment();
            bookingRepository.save(booking);
        }

        return paymentIntentService.create(
                new PaymentIntentService.CreatePaymentCommand(
                        renterId,
                        PaymentType.BOOKING,
                        null,
                        bookingId,
                        PaymentTargetKind.BOOKING,
                        booking.getTotalAmountMinor(),
                        Booking.KES,
                        idempotencyKey
                )
        );
    }

    @Transactional
    public PaymentIntent completeMockPayment(
            UUID renterId,
            String providerReference,
            MockPaymentOutcome outcome,
            Instant completedAt
    ) {
        requireActiveRenter(renterId);
        PaymentIntent intent = paymentIntentService.completeMockCallback(
                renterId, providerReference, outcome, completedAt
        );
        if (intent.getPaymentType() != PaymentType.BOOKING) {
            return intent;
        }

        Booking booking = bookingRepository.findByIdAndRenterId(
                        intent.getBookingId(), renterId
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The booking for this payment no longer exists."
                ));

        if (intent.getStatus() == PaymentStatus.SUCCEEDED) {
            if (booking.getStatus() == BookingStatus.PAYMENT_PENDING) {
                booking.markPaidHeld();
                bookingRepository.save(booking);
                auditService.recordBookingPaidHeld(intent, booking);
            } else if (booking.getStatus() != BookingStatus.PAID_HELD) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The booking is not awaiting payment settlement."
                );
            }
            escrowLedgerService.recordBookingHold(intent, booking);
        } else if (intent.getStatus() == PaymentStatus.FAILED
                && booking.getStatus() == BookingStatus.PAYMENT_PENDING) {
            booking.paymentFailed();
            bookingRepository.save(booking);
        }
        return intent;
    }

    private void requireActiveRenter(UUID renterId) {
        User user = userRepository.findById(renterId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Account is unavailable."
                ));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Account is not active."
            );
        }
        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                renterId, RoleName.RENTER
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Only renters can pay for bookings."
            );
        }
    }
}
