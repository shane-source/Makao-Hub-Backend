package com.makaohub.backend.payment.ledger.service;

import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.payment.ledger.domain.LedgerAccountType;
import com.makaohub.backend.payment.ledger.domain.LedgerComponent;
import com.makaohub.backend.payment.ledger.domain.LedgerEntry;
import com.makaohub.backend.payment.ledger.domain.LedgerSide;
import com.makaohub.backend.payment.ledger.repository.LedgerEntryRepository;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.BookingStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Records balanced mock escrow lines; it does not custody or move money. */
@Service
public class MockEscrowLedgerService {

    private static final int LEGACY_EXPECTED_LINES = 4;
    private static final int COMMISSION_EXPECTED_LINES = 6;

    private final LedgerEntryRepository ledgerEntryRepository;
    private final MockPaymentAuditService auditService;

    public MockEscrowLedgerService(
            LedgerEntryRepository ledgerEntryRepository,
            MockPaymentAuditService auditService
    ) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.auditService = auditService;
    }

    @Transactional
    public void recordBookingHold(PaymentIntent intent, Booking booking) {
        if (intent.getPaymentType() != PaymentType.BOOKING
                || intent.getStatus() != PaymentStatus.SUCCEEDED
                || intent.getBookingId() == null
                || !intent.getBookingId().equals(booking.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only a successful payment for this booking can be ledgered."
            );
        }
        if (booking.getStatus() != BookingStatus.PAID_HELD) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The booking must be paid and held before ledgering."
            );
        }
        if (intent.getAmountMinor() != booking.getTotalAmountMinor()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payment and booking amounts do not match."
            );
        }

        List<LedgerEntry> existing = ledgerEntryRepository
                .findAllByPaymentIntentId(intent.getId());
        if (!existing.isEmpty()) {
            int expectedLines = booking.getCommissionAmountMinor() > 0
                    ? COMMISSION_EXPECTED_LINES
                    : LEGACY_EXPECTED_LINES;
            if (existing.size() != expectedLines) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The escrow ledger transaction is incomplete."
                );
            }
            return;
        }

        UUID transactionId = UUID.randomUUID();
        List<LedgerEntry> lines = new java.util.ArrayList<>(List.of(
                line(transactionId, intent, booking, LedgerComponent.RENT,
                        LedgerSide.DEBIT, booking.getRentAmountMinor()),
                line(transactionId, intent, booking, LedgerComponent.RENT,
                        LedgerSide.CREDIT, booking.getRentAmountMinor()),
                line(transactionId, intent, booking, LedgerComponent.DEPOSIT,
                        LedgerSide.DEBIT, booking.getDepositAmountMinor()),
                line(transactionId, intent, booking, LedgerComponent.DEPOSIT,
                        LedgerSide.CREDIT, booking.getDepositAmountMinor())
        ));
        if (booking.getCommissionAmountMinor() > 0) {
            lines.add(line(transactionId, intent, booking, LedgerComponent.COMMISSION,
                    LedgerSide.DEBIT, booking.getCommissionAmountMinor(),
                    LedgerAccountType.RENTER_CLEARING));
            lines.add(line(transactionId, intent, booking, LedgerComponent.COMMISSION,
                    LedgerSide.CREDIT, booking.getCommissionAmountMinor(),
                    LedgerAccountType.ESCROW_HOLD));
        }
        ledgerEntryRepository.saveAll(lines);
        auditService.recordLedgerPosted(intent, booking);
    }

    @Transactional
    public void recordBookingRelease(
            PaymentIntent intent,
            Booking booking,
            UUID adminUserId
    ) {
        if (intent.getPaymentType() != PaymentType.BOOKING
                || intent.getStatus() != PaymentStatus.SUCCEEDED
                || intent.getBookingId() == null
                || !intent.getBookingId().equals(booking.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only the successful booking payment can be released."
            );
        }
        if (booking.getStatus() != BookingStatus.ACTIVE
                || booking.getReleasedAt() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The booking must be released before payout ledgering."
            );
        }

        List<LedgerEntry> bookingLines = ledgerEntryRepository
                .findAllByBookingId(booking.getId());
        long rentReleased = 0;
        long depositReleased = 0;
        long commissionHeld = 0;
        long commissionRecognized = 0;
        for (LedgerEntry line : bookingLines) {
            if (line.getAccountType() == LedgerAccountType.OWNER_PAYABLE
                    && line.getSide() == LedgerSide.CREDIT) {
                if (line.getComponent() == LedgerComponent.RENT) {
                    rentReleased += line.getAmountMinor();
                } else if (line.getComponent() == LedgerComponent.DEPOSIT) {
                    depositReleased += line.getAmountMinor();
                }
            } else if (line.getComponent() == LedgerComponent.COMMISSION
                    && line.getSide() == LedgerSide.CREDIT) {
                if (line.getAccountType() == LedgerAccountType.ESCROW_HOLD) {
                    commissionHeld += line.getAmountMinor();
                } else if (line.getAccountType() == LedgerAccountType.PLATFORM_REVENUE) {
                    commissionRecognized += line.getAmountMinor();
                }
            }
        }
        if (rentReleased == booking.getRentAmountMinor()
                && depositReleased == booking.getDepositAmountMinor()
                && commissionRecognized == booking.getCommissionAmountMinor()) {
            return;
        }
        if (rentReleased != 0 || depositReleased != 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The payout ledger is incomplete and needs reconciliation."
            );
        }
        // Old test bookings recognized the fee at hold time; leave those
        // immutable ledger lines intact. New bookings hold the fee until release.
        boolean releaseCommission = commissionHeld == booking.getCommissionAmountMinor()
                && commissionRecognized == 0 && commissionHeld > 0;
        boolean legacyCommission = commissionHeld == 0
                && commissionRecognized == booking.getCommissionAmountMinor();
        if (!releaseCommission && !legacyCommission) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The commission ledger is incomplete and needs reconciliation."
            );
        }

        UUID transactionId = UUID.randomUUID();
        List<LedgerEntry> releaseLines = new java.util.ArrayList<>(List.of(
                line(transactionId, intent, booking, LedgerComponent.RENT,
                        LedgerSide.DEBIT, booking.getRentAmountMinor(),
                        LedgerAccountType.ESCROW_HOLD),
                line(transactionId, intent, booking, LedgerComponent.RENT,
                        LedgerSide.CREDIT, booking.getRentAmountMinor(),
                        LedgerAccountType.OWNER_PAYABLE),
                line(transactionId, intent, booking, LedgerComponent.DEPOSIT,
                        LedgerSide.DEBIT, booking.getDepositAmountMinor(),
                        LedgerAccountType.ESCROW_HOLD),
                line(transactionId, intent, booking, LedgerComponent.DEPOSIT,
                        LedgerSide.CREDIT, booking.getDepositAmountMinor(),
                        LedgerAccountType.OWNER_PAYABLE)
        ));
        if (releaseCommission) {
            releaseLines.add(line(transactionId, intent, booking, LedgerComponent.COMMISSION,
                    LedgerSide.DEBIT, booking.getCommissionAmountMinor(),
                    LedgerAccountType.ESCROW_HOLD));
            releaseLines.add(line(transactionId, intent, booking, LedgerComponent.COMMISSION,
                    LedgerSide.CREDIT, booking.getCommissionAmountMinor(),
                    LedgerAccountType.PLATFORM_REVENUE));
        }
        ledgerEntryRepository.saveAll(releaseLines);
        auditService.recordEscrowReleased(intent, booking, adminUserId);
    }

    /** Called only under the transfer/booking row lock after provider verification; retries do not duplicate lines. */
    @Transactional
    public void recordTestTransferSettlement(PaymentIntent intent,Booking booking,boolean reversed) {
        if(booking.getStatus()!=BookingStatus.ACTIVE || booking.getReleasedAt()==null
                || intent.getPaymentType()!=PaymentType.BOOKING || !booking.getId().equals(intent.getBookingId()))
            throw new IllegalStateException("A verified booking release is required.");
        UUID transaction=UUID.randomUUID();
        LedgerAccountType debit=reversed?LedgerAccountType.OWNER_PAYOUT:LedgerAccountType.OWNER_PAYABLE;
        LedgerAccountType credit=reversed?LedgerAccountType.OWNER_PAYABLE:LedgerAccountType.OWNER_PAYOUT;
        ledgerEntryRepository.saveAll(List.of(
            line(transaction,intent,booking,LedgerComponent.RENT,LedgerSide.DEBIT,booking.getRentAmountMinor(),debit),
            line(transaction,intent,booking,LedgerComponent.RENT,LedgerSide.CREDIT,booking.getRentAmountMinor(),credit),
            line(transaction,intent,booking,LedgerComponent.DEPOSIT,LedgerSide.DEBIT,booking.getDepositAmountMinor(),debit),
            line(transaction,intent,booking,LedgerComponent.DEPOSIT,LedgerSide.CREDIT,booking.getDepositAmountMinor(),credit)));
    }

    private static LedgerEntry line(
            UUID transactionId,
            PaymentIntent intent,
            Booking booking,
            LedgerComponent component,
            LedgerSide side,
            long amountMinor
    ) {
        LedgerAccountType account = side == LedgerSide.DEBIT
                ? LedgerAccountType.RENTER_CLEARING
                : LedgerAccountType.ESCROW_HOLD;
        return line(transactionId, intent, booking, component, side, amountMinor, account);
    }

    private static LedgerEntry line(
            UUID transactionId,
            PaymentIntent intent,
            Booking booking,
            LedgerComponent component,
            LedgerSide side,
            long amountMinor,
            LedgerAccountType account
    ) {
        return new LedgerEntry(
                transactionId,
                intent.getId(),
                booking.getId(),
                account,
                component,
                side,
                amountMinor,
                booking.getCurrency()
        );
    }
}
