package com.makaohub.backend.roommate.service;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.messaging.service.ChatContactMaskingService;
import com.makaohub.backend.roommate.api.RoommatePreferencesController.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

/** Role-specific preferences and opt-in-only discovery. No score/ranking is invented. */
@Service
public class RoommatePreferencesService {
    private final JdbcTemplate jdbc;
    private final ChatContactMaskingService masking;
    public RoommatePreferencesService(JdbcTemplate jdbc, ChatContactMaskingService masking) { this.jdbc = jdbc; this.masking = masking; }
    private void requireActor(UUID actor, RoleName role, boolean verified) {
        if (role != RoleName.RENTER && role != RoleName.SUBLESSOR) throw unavailable();
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id
            WHERE u.id=? AND u.account_status='ACTIVE' AND r.name=?
            AND (?=FALSE OR EXISTS(SELECT 1 FROM role_verifications v WHERE v.user_id=u.id AND v.role=r.name AND v.status='VERIFIED')))
            """, Boolean.class, actor, role.name(), verified));
        if (!active) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verified role access required.");
    }
    @Transactional(readOnly=true) public Preferences get(UUID actor, RoleName role) {
        requireActor(actor, role, false);
        return jdbc.query("SELECT * FROM roommate_preferences WHERE user_id=? AND role=?", (rs,n) -> read(rs), actor, role.name())
            .stream().findFirst().orElseThrow(RoommatePreferencesService::unavailable);
    }
    @Transactional public Preferences save(UUID actor, RoleName role, Preferences body) {
        // Serialize edits for this account, so opt-out and profile updates commit atomically.
        jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, actor);
        requireActor(actor, role, body.discoveryOptIn());
        jdbc.update("""
            INSERT INTO roommate_preferences(user_id,role,quiet_social,sleep_schedule,cleanliness,cooking,guests,smoking,pets,
            budget_min_kes,budget_max_kes,discovery_opt_in,area_label,about,employment,move_in_date)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,role) DO UPDATE SET quiet_social=EXCLUDED.quiet_social,
            sleep_schedule=EXCLUDED.sleep_schedule,cleanliness=EXCLUDED.cleanliness,cooking=EXCLUDED.cooking,guests=EXCLUDED.guests,
            smoking=EXCLUDED.smoking,pets=EXCLUDED.pets,budget_min_kes=EXCLUDED.budget_min_kes,budget_max_kes=EXCLUDED.budget_max_kes,
            discovery_opt_in=EXCLUDED.discovery_opt_in,area_label=EXCLUDED.area_label,about=EXCLUDED.about,
            employment=EXCLUDED.employment,move_in_date=EXCLUDED.move_in_date,updated_at=CURRENT_TIMESTAMP
            """, actor,role.name(),body.quietSocial(),body.sleepSchedule(),body.cleanliness(),body.cooking(),body.guests(),body.smoking(),body.pets(),
            body.budgetMinKes(),body.budgetMaxKes(),body.discoveryOptIn(),mask(body.areaLabel()),mask(body.about()),mask(body.employment()),body.moveInDate());
        jdbc.update("INSERT INTO roommate_preference_audit(user_id,role,discovery_opt_in,event) VALUES(?,?,?,'PREFERENCES_SAVED')", actor,role.name(),body.discoveryOptIn());
        return get(actor, role);
    }
    private static final String VISIBLE = """
        FROM roommate_preferences p JOIN users u ON u.id=p.user_id
        JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id AND r.name='RENTER'
        JOIN role_verifications v ON v.user_id=u.id AND v.role='RENTER' AND v.status='VERIFIED'
        WHERE p.role='RENTER' AND p.discovery_opt_in=TRUE AND u.account_status='ACTIVE'
        """;
    private void requireDiscovery(UUID actor) {
        requireActor(actor, RoleName.SUBLESSOR, true);
        if (!get(actor, RoleName.SUBLESSOR).discoveryOptIn()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Opt in before browsing roommate discovery.");
    }
    @Transactional(readOnly=true) public DiscoveryPage discover(UUID actor, int page, int size) {
        requireDiscovery(actor);
        if (page < 0 || page > 10000 || size < 1 || size > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page.");
        var rows = jdbc.query("SELECT p.*,u.full_name " + VISIBLE + " ORDER BY p.user_id LIMIT ? OFFSET ?", (rs,n) -> publicProfile(rs), size, (long)page * size);
        long total = jdbc.queryForObject("SELECT count(*) " + VISIBLE, Long.class);
        return new DiscoveryPage(rows,page,size,total,((long)page+1)*size < total);
    }
    @Transactional(readOnly=true) public PublicProfile profile(UUID actor, UUID renter) {
        requireDiscovery(actor);
        return jdbc.query("SELECT p.*,u.full_name " + VISIBLE + " AND p.user_id=?", (rs,n) -> publicProfile(rs), renter)
            .stream().findFirst().orElseThrow(RoommatePreferencesService::unavailable);
    }
    private PublicProfile publicProfile(ResultSet rs) throws SQLException {
        Preferences p = read(rs);
        // Reuse the platform contact masker so a public about/name cannot bypass protected chat.
        Preferences safe = new Preferences(p.quietSocial(),p.sleepSchedule(),p.cleanliness(),p.cooking(),p.guests(),p.smoking(),p.pets(),
            p.budgetMinKes(),p.budgetMaxKes(),true,mask(p.areaLabel()),mask(p.about()),mask(p.employment()),p.moveInDate());
        return new PublicProfile(rs.getObject("user_id",UUID.class),mask(rs.getString("full_name")),safe);
    }
    private String mask(String text) { return text == null ? null : masking.mask(text).content(); }
    private Preferences read(ResultSet rs) throws SQLException {
        return new Preferences(rs.getString("quiet_social"),rs.getString("sleep_schedule"),rs.getString("cleanliness"),rs.getString("cooking"),
            rs.getString("guests"),rs.getString("smoking"),rs.getString("pets"),rs.getLong("budget_min_kes"),rs.getLong("budget_max_kes"),
            rs.getBoolean("discovery_opt_in"),rs.getString("area_label"),rs.getString("about"),rs.getString("employment"),rs.getObject("move_in_date",LocalDate.class));
    }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Roommate profile unavailable."); }
}
