package com.makaohub.backend.payment.ledger.repository;

import com.makaohub.backend.payment.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findAllByPaymentIntentId(UUID paymentIntentId);

    List<LedgerEntry> findAllByBookingId(UUID bookingId);
}
