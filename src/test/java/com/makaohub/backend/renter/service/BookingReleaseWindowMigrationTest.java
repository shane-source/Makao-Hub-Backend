package com.makaohub.backend.renter.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real Flyway upgrade and PostgreSQL timing guards using synthetic local records only. */
@EnabledIfEnvironmentVariable(named = "MAKAO_ROLE_TEST_DB_URL", matches = ".+")
class BookingReleaseWindowMigrationTest {
    private DriverManagerDataSource source;
    private JdbcTemplate sql;
    private String schema;
    private final UUID renter = UUID.randomUUID(), owner = UUID.randomUUID();
    private static final Instant RENTER_MOVE_IN = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant OWNER_MOVE_IN = RENTER_MOVE_IN.plusSeconds(600);

    @BeforeEach void connect() {
        String url = System.getenv("MAKAO_ROLE_TEST_DB_URL");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/makao_role_test"));
        schema = "release_window_test_" + UUID.randomUUID().toString().replace("-", "");
        source = new DriverManagerDataSource(url + "?currentSchema=" + schema,
                System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_USER", "hp"),
                System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_PASSWORD", ""));
        sql = new JdbcTemplate(source);
    }

    @Test void upgradeRecalculatesHeldBookingsForBothOwnerKindsAndPreservesHistory() {
        flyway("64").migrate();
        users();
        UUID landlord = UUID.randomUUID(), sublessor = UUID.randomUUID(), completed = UUID.randomUUID(), waiting = UUID.randomUUID();
        Instant oldDeadline = RENTER_MOVE_IN.plusSeconds(86400);
        insert(landlord, "LANDLORD_LISTING", OWNER_MOVE_IN, oldDeadline, null);
        insert(sublessor, "SUBLESSOR_LISTING", OWNER_MOVE_IN, oldDeadline, null);
        insert(completed, "LANDLORD_LISTING", OWNER_MOVE_IN, oldDeadline, oldDeadline);
        insert(waiting, "LANDLORD_LISTING", null, oldDeadline, null);

        flyway(null).migrate();

        Instant deadline = OWNER_MOVE_IN.plusSeconds(300);
        assertEquals(deadline, deadline(landlord));
        assertEquals(deadline, deadline(sublessor));
        assertEquals(oldDeadline, deadline(completed));
        assertEquals(oldDeadline, deadline(waiting));
        assertEquals(2, sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='BOOKING_TEST_RELEASE_WINDOW_CHANGED'", Integer.class));
        assertEquals(1L, sql.queryForObject("SELECT version FROM bookings WHERE id=?", Long.class, landlord));
        assertEquals(8400000L, sql.queryForObject("SELECT total_amount_minor FROM bookings WHERE id=?", Long.class, landlord));
        assertEquals("PAID_HELD", sql.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, landlord));
        assertThrows(DataIntegrityViolationException.class, () -> sql.update(
                "UPDATE bookings SET release_eligible_at=? WHERE id=?", timestamp(deadline.minusSeconds(1)), landlord));
        assertThrows(DataIntegrityViolationException.class, () -> release(landlord, deadline.minusSeconds(1)));
        assertThrows(DataIntegrityViolationException.class, () -> sql.update("""
                UPDATE bookings SET status='ACTIVE',released_at=?,release_eligible_at=?,
                    payout_reference='synthetic-payout',release_idempotency_key='synthetic-release' WHERE id=?
                """, timestamp(deadline.minusSeconds(1)), timestamp(deadline.minusSeconds(1)), landlord));
        release(landlord, deadline);
        assertEquals("ACTIVE", sql.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, landlord));
        flyway(null).migrate();
        assertEquals(2, sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='BOOKING_TEST_RELEASE_WINDOW_CHANGED'", Integer.class));
    }

    @Test void cleanDatabaseWaitsForSecondConfirmationAndRejectsMissingDeadline() {
        flyway(null).migrate();
        users();
        UUID booking = UUID.randomUUID();
        insert(booking, "SUBLESSOR_LISTING", null, null, null);
        assertNull(deadline(booking));
        assertThrows(DataIntegrityViolationException.class, () -> sql.update(
                "UPDATE bookings SET owner_move_in_confirmed_at=? WHERE id=?", timestamp(OWNER_MOVE_IN), booking));
        sql.update("UPDATE bookings SET owner_move_in_confirmed_at=?,release_eligible_at=? WHERE id=?",
                timestamp(OWNER_MOVE_IN), timestamp(OWNER_MOVE_IN.plusSeconds(300)), booking);
        assertEquals(OWNER_MOVE_IN.plusSeconds(300), deadline(booking));
        assertThrows(DataIntegrityViolationException.class, () -> release(booking, OWNER_MOVE_IN.plusSeconds(299)));
        release(booking, OWNER_MOVE_IN.plusSeconds(300));
    }

    @Test void ownerFirstUpgradeRequiresFreshResponseAndLeavesCompletedHistoryUntouched() {
        flyway(null).migrate();users();
        UUID held=UUID.randomUUID(),completed=UUID.randomUUID();
        Instant oldDeadline=OWNER_MOVE_IN.plusSeconds(300);
        insert(held,"LANDLORD_LISTING",OWNER_MOVE_IN,oldDeadline,null);
        insert(completed,"SUBLESSOR_LISTING",OWNER_MOVE_IN,oldDeadline,oldDeadline);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        assertNull(sql.queryForObject("SELECT move_in_accepted FROM bookings WHERE id=?",Boolean.class,held));
        assertEquals(oldDeadline,deadline(completed));
        assertEquals("ACTIVE",sql.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,completed));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE booking_id=? AND type='MOVE_IN_REPORTED'",Integer.class,held));
        assertThrows(org.springframework.dao.DataAccessException.class,()->release(held,oldDeadline.plusSeconds(1000)));
        Instant fresh=oldDeadline.plusSeconds(1000);
        sql.update("UPDATE bookings SET move_in_accepted=TRUE,move_in_answered_at=?,move_in_confirmed_at=?,release_eligible_at=?,move_in_response_key='fresh' WHERE id=?",
                timestamp(fresh),timestamp(fresh),timestamp(fresh.plusSeconds(300)),held);
        assertThrows(org.springframework.dao.DataAccessException.class,()->release(held,fresh.plusSeconds(299)));
        release(held,fresh.plusSeconds(300));
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema);
        // Historical V65 policy regression. The owner-first V68+ policy has separate tests.
        config.target(target == null ? "67" : target);
        return config.load();
    }

    private void users() {
        for (UUID id : new UUID[]{renter, owner}) sql.update(
                "INSERT INTO users(id,email,full_name,password_hash) VALUES (?,?,'Synthetic tester','test-only')",
                id, id + "@example.invalid");
    }

    private void insert(UUID id, String kind, Instant ownerMoveIn, Instant eligible, Instant released) {
        sql.update("""
                INSERT INTO bookings(id,renter_id,owner_id,listing_id,listing_kind,source_application_id,
                    rent_amount_minor,deposit_amount_minor,commission_amount_minor,total_amount_minor,currency,status,
                    renter_agreed_at,owner_agreed_at,move_in_confirmed_at,owner_move_in_confirmed_at,release_eligible_at,
                    released_at,payout_reference,release_idempotency_key)
                VALUES (?,?,?,?,?,?,4000000,4000000,400000,8400000,'KES',?,?,?,?,?,?,?,?,?)
                """, id, renter, owner, UUID.randomUUID(), kind, UUID.randomUUID(), released == null ? "PAID_HELD" : "ACTIVE",
                timestamp(RENTER_MOVE_IN), timestamp(RENTER_MOVE_IN), timestamp(RENTER_MOVE_IN), timestamp(ownerMoveIn),
                timestamp(eligible), timestamp(released), released == null ? null : "synthetic-payout",
                released == null ? null : "synthetic-release");
    }

    private Instant deadline(UUID id) {
        Timestamp value = sql.queryForObject("SELECT release_eligible_at FROM bookings WHERE id=?", Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }

    private void release(UUID id, Instant at) {
        sql.update("UPDATE bookings SET status='ACTIVE',released_at=?,payout_reference='synthetic-payout',release_idempotency_key='synthetic-release' WHERE id=?",
                timestamp(at), id);
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
