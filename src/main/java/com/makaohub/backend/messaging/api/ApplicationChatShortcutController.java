package com.makaohub.backend.messaging.api;

import com.makaohub.backend.messaging.service.RentalChatThreadService;
import com.makaohub.backend.messaging.service.RoommateChatThreadService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Resolves an owner's existing conversation without creating or unlocking one. */
@RestController
public class ApplicationChatShortcutController {
    private final JdbcTemplate sql;
    private final RentalChatThreadService rental;
    private final RoommateChatThreadService roommate;

    public ApplicationChatShortcutController(JdbcTemplate sql, RentalChatThreadService rental, RoommateChatThreadService roommate) {
        this.sql = sql; this.rental = rental; this.roommate = roommate;
    }

    @GetMapping("/api/v1/landlord/applications/{applicationId}/conversation")
    public RentalChatThreadResponse landlord(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID applicationId) {
        UUID owner = subject(jwt);
        var ids = sql.query("SELECT t.id FROM rental_chat_threads t JOIN rental_applications a ON a.id=t.rental_application_id JOIN listings l ON l.id=a.listing_id WHERE a.id=? AND l.landlord_id=? AND t.landlord_id=? AND t.renter_id=a.renter_id",
                (rs, row) -> rs.getObject(1, UUID.class), applicationId, owner, owner);
        if (ids.isEmpty()) throw notFound();
        return rental.findForLandlord(owner, ids.getFirst());
    }

    @GetMapping("/api/v1/sublessor/applications/{applicationId}/conversation")
    public RoommateChatThreadResponse sublessor(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID applicationId) {
        UUID owner = subject(jwt);
        var ids = sql.query("SELECT t.id FROM sublessor_rental_applications a JOIN sublessor_listings l ON l.id=a.sublessor_listing_id JOIN roommate_interests i ON i.sublessor_listing_id=a.sublessor_listing_id AND i.renter_id=a.renter_id JOIN roommate_chat_threads t ON t.roommate_interest_id=i.id AND t.renter_id=a.renter_id WHERE a.id=? AND l.sublessor_id=? AND t.sublessor_id=?",
                (rs, row) -> rs.getObject(1, UUID.class), applicationId, owner, owner);
        if (ids.isEmpty()) throw notFound();
        return roommate.findForSublessor(owner, ids.getFirst());
    }

    private static UUID subject(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException failure) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account unavailable."); }
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "No conversation is available for this application."); }
}
