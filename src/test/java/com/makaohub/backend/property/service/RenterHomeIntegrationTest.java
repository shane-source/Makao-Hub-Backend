package com.makaohub.backend.property.service;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MAKAO_ROLE_TEST_DB_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RenterHomeIntegrationTest {
    private final String schema="renter_test_"+UUID.randomUUID().toString().replace("-","");
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private RenterHomeService homes;
    private final UUID renter=UUID.randomUUID(),other=UUID.randomUUID(),owner=UUID.randomUUID(),host=UUID.randomUUID(),whole=UUID.randomUUID(),room=UUID.randomUUID();
    @BeforeAll void setup() {
        String url=System.getenv("MAKAO_ROLE_TEST_DB_URL");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/makao_role_test"));
        source=new DriverManagerDataSource(url+"?currentSchema="+schema,System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_USER","hp"),"");
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc=new JdbcTemplate(source);homes=new RenterHomeService(jdbc);
        user(renter,"RENTER");user(other,"RENTER");user(owner,"LANDLORD");user(host,"SUBLESSOR");
        jdbc.update("INSERT INTO listings(id,landlord_id,title,description,property_type,monthly_rent_kes,bedroom_count,bathroom_count,status) VALUES(?,?,'Synthetic whole home','Synthetic','APARTMENT',45000,2,1,'PUBLISHED')",whole,owner);
        jdbc.update("INSERT INTO sublessor_listings(id,sublessor_id,title,description,space_type,monthly_rent_kes,status) VALUES(?,?,'Synthetic room','Synthetic','SPARE_ROOM',18000,'PUBLISHED')",room,host);
        jdbc.update("INSERT INTO listing_locations(listing_id,area_label,exact_coordinates_ciphertext,approximate_latitude,approximate_longitude,approximate_radius_meters) VALUES(?,'Nairobi',?, -1.29,36.82,500)",whole,new byte[]{1});
        jdbc.update("INSERT INTO sublessor_listing_locations(sublessor_listing_id,area_label,exact_coordinates_ciphertext,approximate_latitude,approximate_longitude,approximate_radius_meters) VALUES(?,'Nairobi',?,-1.29,36.82,500)",room,new byte[]{1});
    }
    private void user(UUID id,String role) {
        jdbc.update("INSERT INTO users(id,full_name,email,password_hash,phone_ciphertext,phone_lookup_hash,account_status) VALUES(?,'Synthetic',?,'synthetic',?,?,'ACTIVE')",id,id+"@example.invalid",new byte[]{1},id.toString());
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name=?",id,role);
        jdbc.update("UPDATE role_verifications SET status='VERIFIED' WHERE user_id=? AND role=?",id,role);
    }
    @AfterAll void clean() { if(source!=null) new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
    @Test void mixedSearchFiltersAndPaginationUseOnlyPublicFields() {
        var page=homes.search(renter,0,1,null,null,null,null,null,null,false);
        assertEquals(1,page.items().size());assertTrue(page.hasNext());
        assertEquals(1,homes.search(renter,1,1,null,null,null,null,null,null,false).items().size());
        assertEquals("SUBLESSOR",homes.search(renter,0,20,"Nairobi",10000L,22000L,null,null,null,false).items().getFirst().source());
        assertThrows(ResponseStatusException.class,()->homes.search(renter,0,51,null,null,null,null,null,null,false));
        assertThrows(ResponseStatusException.class,()->homes.search(renter,0,20,null,30000L,20000L,null,null,null,false));
        assertTrue(Arrays.stream(RenterHomeService.Home.class.getRecordComponents()).noneMatch(c->c.getName().contains("exact")||c.getName().contains("phone")));
    }
    @Test void bookmarksAreOwnedAndDoNotCollideAcrossSources() {
        homes.save(renter,"SUBLESSOR",room);homes.save(renter,"SUBLESSOR",room);
        assertEquals(1,homes.search(renter,0,20,null,null,null,null,null,null,true).items().size());
        assertTrue(homes.search(other,0,20,null,null,null,null,null,null,true).items().isEmpty());
        homes.remove(other,"SUBLESSOR",room);
        assertEquals(1,homes.search(renter,0,20,null,null,null,null,null,null,true).items().size());
        homes.remove(renter,"SUBLESSOR",room);
    }
    @Test void wrongRoleAndUnverifiedOwnersOrRenterCannotBypassReadGuards() {
        assertThrows(ResponseStatusException.class,()->homes.search(owner,0,20,null,null,null,null,null,null,false));
        jdbc.update("UPDATE role_verifications SET status='NOT_STARTED' WHERE user_id=?",host);
        assertThrows(ResponseStatusException.class,()->homes.one(renter,"SUBLESSOR",room));
        assertThrows(ResponseStatusException.class,()->homes.access(renter,room));
        jdbc.update("UPDATE role_verifications SET status='VERIFIED' WHERE user_id=?",host);
        jdbc.update("UPDATE role_verifications SET status='NOT_STARTED' WHERE user_id=?",other);
        assertThrows(ResponseStatusException.class,()->homes.access(other,whole));
        assertEquals(whole,homes.access(renter,whole).id());
    }
    @Test void legacySavedHomesRemainVisibleWithoutBackfill() {
        jdbc.update("INSERT INTO saved_listings(user_id,listing_id) VALUES(?,?)",other,whole);
        try {
            assertEquals(whole,homes.search(other,0,20,null,null,null,null,null,null,true).items().getFirst().id());
            homes.remove(renter,"LANDLORD",whole);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM saved_listings WHERE user_id=?",Integer.class,other));
            homes.remove(other,"LANDLORD",whole);
            assertTrue(homes.search(other,0,20,null,null,null,null,null,null,true).items().isEmpty());
        } finally { jdbc.update("DELETE FROM saved_listings WHERE user_id=?",other); }
    }
}
