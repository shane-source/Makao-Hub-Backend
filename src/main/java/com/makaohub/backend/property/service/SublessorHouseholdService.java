package com.makaohub.backend.property.service;

import com.makaohub.backend.property.api.SublessorHouseholdController.Household;
import com.makaohub.backend.property.api.SublessorHouseholdController.Amenity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SublessorHouseholdService {
    private final JdbcTemplate jdbc;
    public SublessorHouseholdService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private String owned(UUID actor, UUID listing, boolean lock) {
        var rows = jdbc.queryForList("""
            SELECT l.status FROM sublessor_listings l JOIN users u ON u.id=l.sublessor_id
            JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id
            WHERE l.id=? AND l.sublessor_id=? AND u.account_status='ACTIVE' AND r.name='SUBLESSOR'
            """ + (lock ? " FOR UPDATE OF l" : ""), String.class, listing, actor);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shared listing unavailable.");
        return rows.getFirst();
    }
    @Transactional(readOnly=true) public Household get(UUID actor, UUID listing) {
        owned(actor, listing, false);
        return jdbc.query("SELECT * FROM sublessor_household_details WHERE listing_id=?", (rs,n) -> new Household(
            rs.getObject("deposit_kes", Long.class), rs.getString("current_household"), rs.getObject("total_occupants", Integer.class),
            rs.getObject("people_per_room", Integer.class), rs.getString("lifestyle"), parse(rs.getString("amenities"))), listing)
            .stream().findFirst().orElse(new Household(null,null,null,null,null,Set.of()));
    }
    @Transactional public Household save(UUID actor, UUID listing, Household body) {
        if (!"DRAFT".equals(owned(actor, listing, true))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only drafts can be edited.");
        jdbc.update("""
            INSERT INTO sublessor_household_details(listing_id,deposit_kes,current_household,total_occupants,people_per_room,lifestyle,amenities)
            VALUES(?,?,?,?,?,?,?) ON CONFLICT(listing_id) DO UPDATE SET deposit_kes=EXCLUDED.deposit_kes,
            current_household=EXCLUDED.current_household,total_occupants=EXCLUDED.total_occupants,people_per_room=EXCLUDED.people_per_room,
            lifestyle=EXCLUDED.lifestyle,amenities=EXCLUDED.amenities,updated_at=CURRENT_TIMESTAMP
            """, listing, body.depositKes(), body.currentHousehold(), body.totalOccupants(), body.peoplePerRoom(), body.lifestyle(),
            body.amenities().stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        return body;
    }
    private Set<Amenity> parse(String value) { return value.isBlank() ? Set.of() : Arrays.stream(value.split(",")).map(Amenity::valueOf).collect(Collectors.toUnmodifiableSet()); }
}
