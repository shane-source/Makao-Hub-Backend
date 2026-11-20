package com.makaohub.backend.renter.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Makes sure one listing can be booked by only one renter at a time.
 *
 * It takes a database row lock on the listing (SELECT ... FOR UPDATE), so two renters trying at the
 * same moment are handled one after the other: the second sees the first booking and is told the
 * listing is booked. A partial unique index on bookings (migration V83) is the final safety net.
 */
@Service
public class ListingBookingLock {

    static final String BOOKED_MESSAGE = "This listing has already been booked by another renter.";
    static final String UNAVAILABLE_MESSAGE = "This listing is no longer available.";

    private final JdbcTemplate jdbc;

    public ListingBookingLock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Used when a booking is first created from an approved application. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAvailable(String listingKind, UUID listingId, UUID renterId) {
        lockListing(listingKind, listingId);
        requireNoOtherActiveBooking(listingKind, listingId, renterId);
    }

    /** Used when a renter starts paying; reads the listing from the booking itself. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAvailableForBooking(UUID bookingId, UUID renterId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT listing_kind, listing_id FROM bookings WHERE id = ?", bookingId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found.");
        }
        String kind = String.valueOf(rows.get(0).get("listing_kind"));
        UUID listingId = (UUID) rows.get(0).get("listing_id");
        requireAvailable(kind, listingId, renterId);
    }

    private void lockListing(String listingKind, UUID listingId) {
        String table = "SUBLESSOR_LISTING".equals(listingKind) ? "sublessor_listings" : "listings";
        List<String> status = jdbc.queryForList(
                "SELECT status FROM " + table + " WHERE id = ? FOR UPDATE", String.class, listingId);
        if (status.isEmpty() || !"PUBLISHED".equals(status.get(0))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, UNAVAILABLE_MESSAGE);
        }
    }

    private void requireNoOtherActiveBooking(String listingKind, UUID listingId, UUID renterId) {
        Integer taken = jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE listing_kind = ? AND listing_id = ? "
                        + "AND renter_id <> ? AND status IN ('PAYMENT_PENDING','PAID_HELD','ACTIVE')",
                Integer.class, listingKind, listingId, renterId);
        if (taken != null && taken > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, BOOKED_MESSAGE);
        }
    }
}
