package com.makaohub.backend.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tells owners, once per grant, when a plan or boost is about to expire and when it has ended.
 * Notices appear in the app inbox; the existing notification email trigger also emails them.
 */
@Service
@Profile("paystack")
public class OfferExpiryNotifier {
    private static final Logger log = LoggerFactory.getLogger(OfferExpiryNotifier.class);

    private final JdbcTemplate jdbc;

    public OfferExpiryNotifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelay = 60_000, fixedDelayString = "${makao.offers.expiry-poll-ms:900000}")
    @Transactional
    public void run() {
        try {
            scan(false);
            scan(true);
        } catch (RuntimeException failure) {
            log.warn("OFFER_EXPIRY_SCAN_FAILED error={}", failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private record Grant(UUID id, UUID ownerId, String code, UUID listingId, Instant expiresAt) {}

    private void scan(boolean sublessor) {
        String grants = sublessor ? "sublessor_offer_grants" : "landlord_offer_grants";
        String ownerColumn = sublessor ? "sublessor_id" : "landlord_id";
        List<Grant> rows = jdbc.query("SELECT g.id, g." + ownerColumn + " AS owner_id, g.offer_code, g.listing_id, g.expires_at"
                + " FROM " + grants + " g JOIN users u ON u.id = g." + ownerColumn + " AND u.account_status = 'ACTIVE'"
                + " WHERE g.expires_at > CURRENT_TIMESTAMP - INTERVAL '7 days'"
                + " AND g.expires_at <= CURRENT_TIMESTAMP + INTERVAL '3 days'",
                (rs, row) -> new Grant(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                        rs.getString("offer_code"), rs.getObject("listing_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant()));
        Instant now = Instant.now();
        for (Grant grant : rows) {
            boolean plan = grant.listingId() == null;
            String base = grant.code().startsWith("SUB_") ? grant.code().substring(4) : grant.code();
            Duration left = Duration.between(now, grant.expiresAt());
            String kind;
            if (left.isNegative() || left.isZero()) {
                kind = "EXPIRED";
            } else {
                Duration window = plan ? Duration.ofDays(3) : base.endsWith("_1D") ? Duration.ofHours(3) : Duration.ofHours(24);
                if (left.compareTo(window) > 0) continue;
                kind = "EXPIRING";
            }
            if (plan && renewed(grants, ownerColumn, grant)) continue;
            int inserted = jdbc.update("INSERT INTO offer_expiry_notices(grant_id, kind) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    grant.id(), kind);
            if (inserted == 0) continue;
            publish(grant, base, plan, kind, left, sublessor);
        }
    }

    private boolean renewed(String grants, String ownerColumn, Grant grant) {
        Boolean later = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + grants + " WHERE " + ownerColumn
                + " = ? AND listing_id IS NULL AND id <> ? AND expires_at > ?)",
                Boolean.class, grant.ownerId(), grant.id(), java.sql.Timestamp.from(grant.expiresAt()));
        return Boolean.TRUE.equals(later);
    }

    private void publish(Grant grant, String base, boolean plan, String kind, Duration left, boolean sublessor) {
        String type;
        String title;
        String body;
        if (plan) {
            String name = base.startsWith("PRO_MAX") ? "Pro Max" : "Pro";
            type = "EXPIRING".equals(kind) ? "PLAN_EXPIRING" : "PLAN_EXPIRED";
            if ("EXPIRING".equals(kind)) {
                title = "Your " + name + " plan expires " + in(left);
                body = "Renew before it ends to keep your photo limit and listing slots. Photos you already added are kept either way.";
            } else {
                title = "Your " + name + " plan has ended";
                body = "You're back on the Free plan (2 photos per listing, 1 active listing). Your existing photos are kept. Upgrade any time to add more.";
            }
        } else {
            String listing = listingTitle(grant, sublessor);
            type = "EXPIRING".equals(kind) ? "BOOST_EXPIRING" : "BOOST_EXPIRED";
            if ("EXPIRING".equals(kind)) {
                title = "Your boost ends " + in(left);
                body = "The boost on \"" + listing + "\" is about to end. Boost again to keep reaching more renters.";
            } else {
                title = "Your boost has ended";
                body = "The boost on \"" + listing + "\" has ended. Boost again to reach more renters.";
            }
        }
        jdbc.update("INSERT INTO notifications(user_id, type, title, body) VALUES (?, ?, ?, ?)",
                grant.ownerId(), type, cut(title, 150), cut(body, 500));
    }

    private String listingTitle(Grant grant, boolean sublessor) {
        String table = sublessor ? "sublessor_listings" : "listings";
        String found = jdbc.query("SELECT title FROM " + table + " WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, grant.listingId());
        return found == null || found.isBlank() ? "your listing" : cut(found, 80);
    }

    private static String in(Duration left) {
        long hours = Math.max(1, left.toHours());
        if (hours < 24) return "in " + hours + (hours == 1 ? " hour" : " hours");
        long days = (hours + 23) / 24;
        return "in " + days + (days == 1 ? " day" : " days");
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
