package com.makaohub.backend.roommate.service;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.messaging.service.ChatContactMaskingService;
import com.makaohub.backend.property.api.SublessorHouseholdController.*;
import com.makaohub.backend.property.service.SublessorHouseholdService;
import com.makaohub.backend.roommate.api.RoommatePreferencesController.Preferences;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MAKAO_ROLE_TEST_DB_URL", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharedHomeIntegrationTest {
    private final String schema = "shared_test_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private RoommatePreferencesService preferences;
    private SublessorHouseholdService household;
    private final UUID owner=UUID.randomUUID(), other=UUID.randomUUID(), renter=UUID.randomUUID(), unverified=UUID.randomUUID(), listing=UUID.randomUUID();
    @BeforeAll void setup() {
        String url=System.getenv("MAKAO_ROLE_TEST_DB_URL");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/makao_role_test"));
        source=new DriverManagerDataSource(url+"?currentSchema="+schema,System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_USER","hp"),System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_PASSWORD",""));
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc=new JdbcTemplate(source); preferences=new RoommatePreferencesService(jdbc,new ChatContactMaskingService()); household=new SublessorHouseholdService(jdbc);
        user(owner,"SUBLESSOR",true); user(other,"SUBLESSOR",true); user(renter,"RENTER",true); user(unverified,"RENTER",false);
        jdbc.update("INSERT INTO sublessor_listings(id,sublessor_id,title,description,space_type,monthly_rent_kes) VALUES(?,?,'Synthetic shared home','Synthetic description','SPARE_ROOM',18000)",listing,owner);
        preferences.save(owner,RoleName.SUBLESSOR,body(true));
        preferences.save(renter,RoleName.RENTER,body(true));
    }
    private void user(UUID id,String role,boolean verified) {
        jdbc.update("INSERT INTO users(id,full_name,email,password_hash,phone_ciphertext,phone_lookup_hash,account_status) VALUES(?,'Synthetic Person',?,'synthetic',?,?,'ACTIVE')",id,id+"@example.invalid",new byte[]{1},id.toString());
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name=?",id,role);
        // The existing role-assignment trigger creates NOT_STARTED; configure only our synthetic fixtures.
        jdbc.update("INSERT INTO role_verifications(user_id,role,status) VALUES(?,?,?) ON CONFLICT(user_id,role) DO UPDATE SET status=EXCLUDED.status",id,role,verified?"VERIFIED":"NOT_STARTED");
    }
    private Preferences body(boolean optIn) { return new Preferences("QUIET","EARLY","DAILY","REGULAR","ASK_FIRST","NO_INDOOR","NONE",15000,22000,optIn,"Nairobi","Synthetic public profile",null,null); }
    @AfterAll void cleanup() { if(source!=null) new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }

    @Test void householdIsOwnedAndContainsIntegerMoneyWithoutBackfillingOtherListings() {
        var value=new Household(18000L,"One adult",2,1,"Quiet evenings",Set.of(Amenity.WIFI,Amenity.WATER));
        assertNull(household.get(owner,listing).depositKes());
        household.save(owner,listing,value);
        assertEquals(value,household.get(owner,listing));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->household.get(other,listing)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->household.save(other,listing,value)).getStatusCode().value());
        jdbc.update("UPDATE sublessor_listings SET status='PUBLISHED' WHERE id=?",listing);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->household.save(owner,listing,value)).getStatusCode().value());
        jdbc.update("UPDATE sublessor_listings SET status='DRAFT' WHERE id=?",listing);
    }
    @Test void optOutImmediatelyRemovesDiscoveryAndDirectProfileAccess() {
        preferences.save(renter,RoleName.RENTER,body(true));
        assertTrue(preferences.discover(owner,0,20).items().stream().anyMatch(p->p.userId().equals(renter)));
        preferences.save(renter,RoleName.RENTER,body(false));
        assertFalse(preferences.discover(owner,0,20).items().stream().anyMatch(p->p.userId().equals(renter)));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->preferences.profile(owner,renter)).getStatusCode().value());
        preferences.save(renter,RoleName.RENTER,body(true));
    }
    @Test void unverifiedWrongRoleAndOptedOutHostsCannotDiscover() {
        assertThrows(ResponseStatusException.class,()->preferences.save(unverified,RoleName.RENTER,body(true)));
        preferences.save(unverified,RoleName.RENTER,body(false));
        jdbc.update("UPDATE roommate_preferences SET discovery_opt_in=TRUE WHERE user_id=?",unverified);
        assertFalse(preferences.discover(owner,0,50).items().stream().anyMatch(p->p.userId().equals(unverified)));
        assertThrows(ResponseStatusException.class,()->preferences.discover(renter,0,20));
        preferences.save(other,RoleName.SUBLESSOR,body(false));
        assertThrows(ResponseStatusException.class,()->preferences.discover(other,0,20));
    }
    @Test void publicResponsesContainOnlyMaskedOptedInPreferences() {
        var p=body(true);
        preferences.save(renter,RoleName.RENTER,new Preferences(p.quietSocial(),p.sleepSchedule(),p.cleanliness(),p.cooking(),p.guests(),p.smoking(),p.pets(),15000,22000,true,"Nairobi","Email synthetic@example.invalid or 0712345678",null,null));
        var profile=preferences.profile(owner,renter);
        assertFalse(profile.preferences().about().contains("@example.invalid"));
        assertFalse(profile.preferences().about().contains("0712345678"));
        assertFalse(jdbc.queryForObject("SELECT about FROM roommate_preferences WHERE user_id=?",String.class,renter).contains("0712345678"));
        preferences.save(renter,RoleName.RENTER,body(true));
    }
    @Test void pagesAreBoundedAndAuditRowsCannotBeRewritten() {
        assertThrows(ResponseStatusException.class,()->preferences.discover(owner,-1,20));
        assertThrows(ResponseStatusException.class,()->preferences.discover(owner,0,51));
        assertThrows(Exception.class,()->jdbc.update("UPDATE roommate_preference_audit SET discovery_opt_in=FALSE WHERE user_id=?",owner));
        assertThrows(Exception.class,()->jdbc.update("DELETE FROM roommate_preference_audit WHERE user_id=?",owner));
    }
}
