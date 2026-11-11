package com.makaohub.backend.property.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Public-only union. Exact coordinates, contact and evidence columns are never selected. */
@Service
public class RenterHomeService {
    public record Home(UUID id, String source, String title, String description, String propertyType,
        long monthlyRentKes, Integer bedroomCount, short bathroomCount, boolean furnished, LocalDate availableFrom,
        String areaLabel, BigDecimal approximateLatitude, BigDecimal approximateLongitude, short approximateRadiusMeters,
        boolean sponsored) {}
    public record HomePage(List<Home> items, int page, boolean hasNext) {}
    private final JdbcTemplate jdbc;
    public RenterHomeService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String HOMES = """
        (SELECT l.id,'LANDLORD' AS source,l.title,l.description,l.property_type,l.monthly_rent_kes,
        l.bedroom_count::integer,l.bathroom_count,l.furnished,l.available_from,p.area_label,
        p.approximate_latitude,p.approximate_longitude,p.approximate_radius_meters,l.updated_at,
        EXISTS(SELECT 1 FROM landlord_offer_grants g WHERE g.listing_id=l.id
          AND g.starts_at<=CURRENT_TIMESTAMP AND g.expires_at>CURRENT_TIMESTAMP) AS sponsored
        FROM listings l JOIN listing_locations p ON p.listing_id=l.id JOIN users u ON u.id=l.landlord_id
        JOIN role_verifications v ON v.user_id=u.id AND v.role='LANDLORD' AND v.status='VERIFIED'
        WHERE l.status='PUBLISHED' AND u.account_status='ACTIVE'
        AND EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.name='LANDLORD')
        UNION ALL
        SELECT l.id,'SUBLESSOR',l.title,l.description,l.space_type,l.monthly_rent_kes,
        NULL::integer,l.bathroom_count,l.furnished,l.available_from,p.area_label,
        p.approximate_latitude,p.approximate_longitude,p.approximate_radius_meters,l.updated_at,
        EXISTS(SELECT 1 FROM sublessor_offer_grants g WHERE g.listing_id=l.id
          AND g.starts_at<=CURRENT_TIMESTAMP AND g.expires_at>CURRENT_TIMESTAMP) AS sponsored
        FROM sublessor_listings l JOIN sublessor_listing_locations p ON p.sublessor_listing_id=l.id
        JOIN users u ON u.id=l.sublessor_id JOIN role_verifications v ON v.user_id=u.id AND v.role='SUBLESSOR' AND v.status='VERIFIED'
        WHERE l.status='PUBLISHED' AND u.account_status='ACTIVE'
        AND EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.name='SUBLESSOR')) h
        """;
    private void actor(UUID actor) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id
            WHERE u.id=? AND u.account_status='ACTIVE' AND r.name='RENTER')
            """, Boolean.class, actor))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Renter access required.");
    }
    private Home map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Home(rs.getObject("id",UUID.class),rs.getString("source"),rs.getString("title"),rs.getString("description"),
            rs.getString("property_type"),rs.getLong("monthly_rent_kes"),rs.getObject("bedroom_count",Integer.class),
            rs.getShort("bathroom_count"),rs.getBoolean("furnished"),rs.getObject("available_from",LocalDate.class),
            rs.getString("area_label"),rs.getBigDecimal("approximate_latitude"),rs.getBigDecimal("approximate_longitude"),rs.getShort("approximate_radius_meters"),
            rs.getBoolean("sponsored"));
    }
    @Transactional(readOnly=true)
    public HomePage search(UUID actor, int page, int size, String area, Long min, Long max, String source, Integer bedrooms, Boolean furnished,
                           String propertyType, LocalDate availableBy, boolean saved) {
        actor(actor);
        if(page<0 || page>10000 || size<1 || size>50 || area!=null && area.length()>150 || min!=null && min<0 ||
           max!=null && max<0 || min!=null && max!=null && min>max || bedrooms!=null && (bedrooms<0 || bedrooms>20) ||
           propertyType!=null && !Set.of("APARTMENT","HOUSE","STUDIO","BEDSITTER","ROOM","SPARE_ROOM","SERVANT_QUARTER").contains(propertyType))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid filters.");
        var sql = new StringBuilder("SELECT h.* FROM ").append(HOMES).append(" WHERE TRUE");
        var args = new ArrayList<Object>();
        if(area!=null && !area.isBlank()) { sql.append(" AND position(lower(?) in lower(h.area_label))>0"); args.add(area.strip()); }
        if(min!=null) { sql.append(" AND h.monthly_rent_kes>=?"); args.add(min); }
        if(max!=null) { sql.append(" AND h.monthly_rent_kes<=?"); args.add(max); }
        if(source!=null && !source.isBlank()) { source(source); sql.append(" AND h.source=?"); args.add(source); }
        if(bedrooms!=null) { sql.append(" AND h.bedroom_count>=?"); args.add(bedrooms); }
        if(furnished!=null) { sql.append(" AND h.furnished=?"); args.add(furnished); }
        if(propertyType!=null) { sql.append(" AND h.property_type=?"); args.add(propertyType); }
        if(availableBy!=null) { sql.append(" AND h.available_from IS NOT NULL AND h.available_from<=?"); args.add(availableBy); }
        if(saved) { sql.append(" AND (EXISTS(SELECT 1 FROM renter_saved_homes s WHERE s.renter_id=? AND s.source=h.source AND s.listing_id=h.id) OR (h.source='LANDLORD' AND EXISTS(SELECT 1 FROM saved_listings s WHERE s.user_id=? AND s.listing_id=h.id)))"); args.add(actor); args.add(actor); }
        sql.append(" ORDER BY h.sponsored DESC,h.updated_at DESC,h.source,h.id LIMIT ? OFFSET ?"); args.add(size+1); args.add((long)page*size);
        var rows = jdbc.query(sql.toString(),this::map,args.toArray());
        return new HomePage(List.copyOf(rows.subList(0,Math.min(size,rows.size()))),page,rows.size()>size);
    }
    /** Check visibility without counting internal photo lookups as listing views. */
    @Transactional(readOnly=true) public Home available(UUID actor, String source, UUID id) {
        actor(actor); source(source);
        return jdbc.query("SELECT h.* FROM "+HOMES+" WHERE h.source=? AND h.id=?",this::map,source,id)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Home unavailable."));
    }
    @Transactional public Home one(UUID actor, String source, UUID id) {
        Home home = available(actor, source, id);
        if ("LANDLORD".equals(source)) jdbc.update("""
            INSERT INTO landlord_listing_view_counts(listing_id, detail_opens) VALUES (?, 1)
            ON CONFLICT (listing_id) DO UPDATE SET detail_opens = landlord_listing_view_counts.detail_opens + 1
            """, id);
        else jdbc.update("""
            INSERT INTO sublessor_listing_view_counts(listing_id, detail_opens) VALUES (?, 1)
            ON CONFLICT (listing_id) DO UPDATE SET detail_opens = sublessor_listing_view_counts.detail_opens + 1
            """, id);
        if ("LANDLORD".equals(source)) jdbc.update("""
            INSERT INTO landlord_listing_daily_views(listing_id, view_date, detail_opens)
            VALUES (?, (CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Nairobi')::date, 1)
            ON CONFLICT (listing_id, view_date) DO UPDATE SET detail_opens=landlord_listing_daily_views.detail_opens+1
            """, id);
        else jdbc.update("""
            INSERT INTO sublessor_listing_daily_views(listing_id, view_date, detail_opens)
            VALUES (?, (CURRENT_TIMESTAMP AT TIME ZONE 'Africa/Nairobi')::date, 1)
            ON CONFLICT (listing_id, view_date) DO UPDATE SET detail_opens=sublessor_listing_daily_views.detail_opens+1
            """, id);
        return home;
    }
    @Transactional public void save(UUID actor, String source, UUID id) {
        actor(actor); source(source);
        if (jdbc.query("SELECT h.id FROM "+HOMES+" WHERE h.source=? AND h.id=?",
                (rs, row) -> rs.getObject(1, UUID.class), source, id).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Home unavailable.");
        jdbc.update("INSERT INTO renter_saved_homes(renter_id,source,listing_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",actor,source,id);
    }
    @Transactional public void remove(UUID actor, String source, UUID id) {
        actor(actor); source(source);
        jdbc.update("DELETE FROM renter_saved_homes WHERE renter_id=? AND source=? AND listing_id=?",actor,source,id);
        if(source.equals("LANDLORD")) jdbc.update("DELETE FROM saved_listings WHERE user_id=? AND listing_id=?",actor,id);
    }
    @Transactional(readOnly=true) public Home access(UUID actor, UUID listing) {
        actor(actor);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM role_verifications WHERE user_id=? AND role='RENTER' AND status='VERIFIED')",Boolean.class,actor)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Renter verification is required.");
        var rows=jdbc.query("SELECT h.* FROM "+HOMES+" WHERE h.id=?",this::map,listing);
        if(rows.size()!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Home unavailable.");
        return rows.getFirst();
    }
    private void source(String source) {
        if(!List.of("LANDLORD","SUBLESSOR").contains(source)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid listing source.");
    }
}
