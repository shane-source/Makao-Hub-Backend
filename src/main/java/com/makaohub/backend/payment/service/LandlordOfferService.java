package com.makaohub.backend.payment.service;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.verification.service.RoleVerificationGuard;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Server-owned catalogue, verified purchase guard and time-limited grants. */
@Service
public class LandlordOfferService {
    private final JdbcTemplate jdbc;
    private final ListingRepository listings;
    private final UserRepository users;
    private final RoleVerificationGuard verification;

    public LandlordOfferService(JdbcTemplate jdbc, ListingRepository listings,
            UserRepository users, RoleVerificationGuard verification) {
        this.jdbc = jdbc;
        this.listings = listings;
        this.users = users;
        this.verification = verification;
    }

    public LandlordOffer requireOffer(UUID landlordId, String code, UUID listingId) {
        LandlordOffer offer;
        try { offer = LandlordOffer.valueOf(code == null ? "" : code); }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown landlord offer.");
        }
        verification.requireVerified(landlordId, RoleName.LANDLORD);
        if (offer.paymentType == com.makaohub.backend.payment.domain.PaymentType.BOOST) {
            if (listingId == null || listings.findByIdAndLandlordId(listingId, landlordId)
                    .filter(listing -> listing.getStatus() == ListingStatus.PUBLISHED).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Published owned listing not found.");
            }
        } else if (listingId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan cannot target a listing.");
        }
        return offer;
    }

    public LandlordOffer requireSublessorOffer(UUID sublessorId, String code, UUID listingId) {
        LandlordOffer offer;
        try {
            if (code == null || !code.startsWith("SUB_")) throw new IllegalArgumentException();
            offer = LandlordOffer.valueOf(code.substring(4));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown sublessor offer.");
        }
        verification.requireVerified(sublessorId, RoleName.SUBLESSOR);
        if (offer.paymentType == com.makaohub.backend.payment.domain.PaymentType.BOOST) {
            Boolean owned = listingId == null ? false : jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM sublessor_listings
                  WHERE id=? AND sublessor_id=? AND status='PUBLISHED')
                """, Boolean.class, listingId, sublessorId);
            if (!Boolean.TRUE.equals(owned)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Published owned listing not found.");
            }
        } else if (listingId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan cannot target a listing.");
        }
        return offer;
    }

    @Transactional
    public void activate(PaymentIntent intent) {
        if (intent.getStatus() != PaymentStatus.SUCCEEDED || intent.getOfferCode() == null) return;
        boolean sublessor = intent.getOfferCode().startsWith("SUB_");
        LandlordOffer offer = LandlordOffer.valueOf(sublessor ? intent.getOfferCode().substring(4) : intent.getOfferCode());
        if (intent.getPaymentType() != offer.paymentType || intent.getAmountMinor() != offer.amountMinor
                || !PaymentIntent.KES.equals(intent.getCurrency())
                || (offer.paymentType == com.makaohub.backend.payment.domain.PaymentType.BOOST)
                    != (intent.getListingId() != null)
                || (sublessor && offer.paymentType == com.makaohub.backend.payment.domain.PaymentType.BOOST
                    && intent.getTargetKind() != com.makaohub.backend.payment.domain.PaymentTargetKind.SUBLESSOR_LISTING)) {
            throw new IllegalStateException("Paid owner offer does not match its server quote.");
        }
        Instant start = intent.getCompletedAt();
        if (sublessor) {
            jdbc.update("""
                INSERT INTO sublessor_offer_grants(payment_intent_id, sublessor_id, listing_id, offer_code, starts_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (payment_intent_id) DO NOTHING
                """, intent.getId(), intent.getPayerUserId(), intent.getListingId(), intent.getOfferCode(),
                    Timestamp.from(start), Timestamp.from(start.plus(offer.duration)));
            return;
        }
        jdbc.update("""
            INSERT INTO landlord_offer_grants(payment_intent_id, landlord_id, listing_id, offer_code, starts_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (payment_intent_id) DO NOTHING
            """, intent.getId(), intent.getPayerUserId(), intent.getListingId(), offer.name(),
                Timestamp.from(start), Timestamp.from(start.plus(offer.duration)));
    }

    @Transactional(readOnly = true)
    public int activeListingLimit(UUID landlordId) {
        String code = jdbc.query("""
            SELECT offer_code FROM landlord_offer_grants
            WHERE landlord_id = ? AND listing_id IS NULL AND starts_at <= CURRENT_TIMESTAMP
              AND expires_at > CURRENT_TIMESTAMP
            ORDER BY CASE offer_code WHEN 'PRO_MAX_30D' THEN 2 ELSE 1 END DESC LIMIT 1
            """, rs -> rs.next() ? rs.getString(1) : null, landlordId);
        return code == null ? 1 : LandlordOffer.valueOf(code).listingLimit;
    }

    @Transactional(readOnly = true)
    public int activeSublessorListingLimit(UUID sublessorId) {
        String code = jdbc.query("""
            SELECT offer_code FROM sublessor_offer_grants
            WHERE sublessor_id=? AND listing_id IS NULL AND starts_at<=CURRENT_TIMESTAMP
              AND expires_at>CURRENT_TIMESTAMP
            ORDER BY CASE offer_code WHEN 'SUB_PRO_MAX_30D' THEN 2 ELSE 1 END DESC LIMIT 1
            """, rs -> rs.next() ? rs.getString(1) : null, sublessorId);
        return code == null ? 1 : LandlordOffer.valueOf(code.substring(4)).listingLimit;
    }

    @Transactional
    public void requirePublicationSlot(UUID landlordId) {
        users.findByIdForUpdate(landlordId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Landlord not found."));
        long published = listings.countByLandlordIdAndStatus(landlordId, ListingStatus.PUBLISHED);
        if (published >= activeListingLimit(landlordId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Your active listing limit is reached. Upgrade your plan or pause a listing.");
        }
    }

    @Transactional
    public void requireSublessorPublicationSlot(UUID sublessorId) {
        users.findByIdForUpdate(sublessorId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Sublessor not found."));
        Long published = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sublessor_listings WHERE sublessor_id=? AND status='PUBLISHED'
            """, Long.class, sublessorId);
        if (published != null && published >= activeSublessorListingLimit(sublessorId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Your active listing limit is reached. Upgrade your plan or pause a listing.");
        }
    }

    @Transactional(readOnly = true)
    public boolean isBoosted(UUID listingId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM landlord_offer_grants
            WHERE listing_id = ? AND starts_at <= CURRENT_TIMESTAMP AND expires_at > CURRENT_TIMESTAMP
            """, Integer.class, listingId);
        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public List<ListingInsight> insights(UUID landlordId) {
        if (activeListingLimit(landlordId) == 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An active Pro plan is required for insights.");
        }
        return jdbc.query("""
            SELECT l.id, l.title, COALESCE(v.detail_opens, 0) AS detail_opens,
              (SELECT COUNT(*) FROM rental_applications a WHERE a.listing_id = l.id) AS enquiries
            FROM listings l LEFT JOIN landlord_listing_view_counts v ON v.listing_id = l.id
            WHERE l.landlord_id = ? ORDER BY l.updated_at DESC
            """, (rs, row) -> new ListingInsight(rs.getObject("id", UUID.class),
                rs.getString("title"), rs.getLong("detail_opens"), rs.getLong("enquiries")), landlordId);
    }

    @Transactional(readOnly = true)
    public List<ListingInsight> sublessorInsights(UUID sublessorId) {
        if (activeSublessorListingLimit(sublessorId) == 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An active Pro plan is required for insights.");
        }
        return jdbc.query("""
            SELECT l.id, l.title, COALESCE(v.detail_opens, 0) AS detail_opens,
              (SELECT COUNT(*) FROM sublessor_rental_applications a WHERE a.sublessor_listing_id=l.id) AS enquiries
            FROM sublessor_listings l LEFT JOIN sublessor_listing_view_counts v ON v.listing_id=l.id
            WHERE l.sublessor_id=? ORDER BY l.updated_at DESC
            """, (rs, row) -> new ListingInsight(rs.getObject("id", UUID.class),
                rs.getString("title"), rs.getLong("detail_opens"), rs.getLong("enquiries")), sublessorId);
    }

    public record ListingInsight(UUID listingId, String title, long detailOpens, long enquiries) {}

    /** Basic owner-only card metrics; the Pro insights endpoint remains unchanged. */
    @Transactional(readOnly = true)
    public List<ListingCardMetrics> cardMetrics(UUID owner, boolean sublessor) {
        String listingTable = sublessor ? "sublessor_listings" : "listings";
        String ownerColumn = sublessor ? "sublessor_id" : "landlord_id";
        String viewsTable = sublessor ? "sublessor_listing_daily_views" : "landlord_listing_daily_views";
        String grantsTable = sublessor ? "sublessor_offer_grants" : "landlord_offer_grants";
        String role = sublessor ? "SUBLESSOR" : "LANDLORD";
        return jdbc.query("SELECT l.id, COALESCE(v.detail_opens, 0) AS views_today, "
                + "(SELECT MAX(g.expires_at) FROM " + grantsTable + " g WHERE g.listing_id=l.id AND g." + ownerColumn + "=? "
                + "AND g.starts_at<=CURRENT_TIMESTAMP AND g.expires_at>CURRENT_TIMESTAMP) AS boost_expires_at "
                + "FROM " + listingTable + " l JOIN users u ON u.id=l." + ownerColumn + " AND u.account_status='ACTIVE' "
                + "LEFT JOIN " + viewsTable + " v ON v.listing_id=l.id AND v.view_date=(CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Nairobi')::date "
                + "WHERE l." + ownerColumn + "=? AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.name=?)",
                (rs, row) -> new ListingCardMetrics(rs.getObject("id", UUID.class), rs.getLong("views_today"),
                        rs.getTimestamp("boost_expires_at") == null ? null : rs.getTimestamp("boost_expires_at").toInstant()), owner, owner, role);
    }

    public record ListingCardMetrics(UUID listingId, long viewsToday, Instant boostExpiresAt) {}

    @Transactional(readOnly = true)
    public List<ActiveGrant> activeGrants(UUID landlordId) {
        return jdbc.query("""
            SELECT offer_code, listing_id, expires_at FROM landlord_offer_grants
            WHERE landlord_id = ? AND starts_at <= CURRENT_TIMESTAMP AND expires_at > CURRENT_TIMESTAMP
            ORDER BY expires_at ASC
            """, (rs, row) -> new ActiveGrant(rs.getString("offer_code"),
                rs.getObject("listing_id", UUID.class), rs.getTimestamp("expires_at").toInstant()), landlordId);
    }

    @Transactional(readOnly = true)
    public List<ActiveGrant> activeSublessorGrants(UUID sublessorId) {
        return jdbc.query("""
            SELECT offer_code, listing_id, expires_at FROM sublessor_offer_grants
            WHERE sublessor_id=? AND starts_at<=CURRENT_TIMESTAMP AND expires_at>CURRENT_TIMESTAMP
            ORDER BY expires_at ASC
            """, (rs, row) -> new ActiveGrant(rs.getString("offer_code"),
                rs.getObject("listing_id", UUID.class), rs.getTimestamp("expires_at").toInstant()), sublessorId);
    }

    public record ActiveGrant(String code, UUID listingId, Instant expiresAt) {}
}
