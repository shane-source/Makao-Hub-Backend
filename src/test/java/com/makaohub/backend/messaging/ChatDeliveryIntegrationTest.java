package com.makaohub.backend.messaging;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.repository.*;
import com.makaohub.backend.messaging.api.AcknowledgeChatMessagesRequest;
import com.makaohub.backend.messaging.api.SendRentalChatMessageRequest;
import com.makaohub.backend.messaging.api.SendRoommateChatMessageRequest;
import com.makaohub.backend.messaging.domain.*;
import com.makaohub.backend.messaging.repository.*;
import com.makaohub.backend.messaging.service.*;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import jakarta.persistence.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL locks, role/ownership checks and rollback; synthetic data only. */
@EnabledIfEnvironmentVariable(named = "MAKAO_ROLE_TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatDeliveryIntegrationTest {
    private final String schema = "chat_delivery_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate sql;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate tx;
    private EntityManager em;
    private RentalChatMessageService rental;
    private RoommateChatMessageService roommate;
    private RentalChatMessageRepository rentalMessages;
    private RoommateChatMessageRepository roomMessages;
    private UUID renter, landlord, sublessor, outsider, rentalThread, roomThread;

    @BeforeAll void setup() {
        String url = System.getenv("MAKAO_ROLE_TEST_DB_URL");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/makao_role_test"));
        var data = new DriverManagerDataSource(url + "?currentSchema=" + schema,
                System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_USER", "hp"),
                System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_PASSWORD", ""));
        Flyway.configure().dataSource(data).schemas(schema).defaultSchema(schema).load().migrate();
        sql = new JdbcTemplate(data);
        renter = user(RoleName.RENTER); landlord = user(RoleName.LANDLORD);
        sublessor = user(RoleName.SUBLESSOR); outsider = user(RoleName.RENTER);
        var listing = UUID.randomUUID(); var application = UUID.randomUUID(); rentalThread = UUID.randomUUID();
        sql.update("INSERT INTO listings(id,landlord_id,title,description,property_type,monthly_rent_kes,bedroom_count,bathroom_count) VALUES (?,?,'Synthetic home','Synthetic','HOUSE',10000,1,1)", listing, landlord);
        sql.update("INSERT INTO rental_applications(id,renter_id,listing_id) VALUES (?,?,?)", application, renter, listing);
        sql.update("INSERT INTO rental_chat_threads(id,rental_application_id,renter_id,landlord_id) VALUES (?,?,?,?)", rentalThread, application, renter, landlord);
        var sharedListing = UUID.randomUUID(); var interest = UUID.randomUUID(); roomThread = UUID.randomUUID();
        sql.update("INSERT INTO sublessor_listings(id,sublessor_id,title,description,space_type,monthly_rent_kes) VALUES (?,?,'Synthetic room','Synthetic','SPARE_ROOM',10000)", sharedListing, sublessor);
        sql.update("INSERT INTO roommate_interests(id,renter_id,sublessor_listing_id) VALUES (?,?,?)", interest, renter, sharedListing);
        sql.update("INSERT INTO roommate_chat_threads(id,roommate_interest_id,renter_id,sublessor_id) VALUES (?,?,?,?)", roomThread, interest, renter, sublessor);
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(data);
        factory.setPackagesToScan("com.makaohub.backend.auth.domain", "com.makaohub.backend.messaging.domain", "com.makaohub.backend.verification.domain");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate")); factory.afterPropertiesSet();
        var emf = Objects.requireNonNull(factory.getObject());
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);
        tx = new TransactionTemplate(new JpaTransactionManager(emf));
        var repos = new JpaRepositoryFactory(em);
        var roles = repos.getRepository(UserRoleRepository.class);
        rentalMessages = repos.getRepository(RentalChatMessageRepository.class);
        roomMessages = repos.getRepository(RoommateChatMessageRepository.class);
        var limiter = mock(ChatMessageRateLimiter.class);
        var numericCounter = new ChatNumericGroupCounter(sql);
        rental = new RentalChatMessageService(em, roles, repos.getRepository(RoleVerificationRepository.class),
                repos.getRepository(RentalChatThreadRepository.class), rentalMessages, new ChatContactMaskingService(), numericCounter, limiter);
        roommate = new RoommateChatMessageService(em, repos.getRepository(UserRepository.class), roles,
                repos.getRepository(RoommateChatThreadRepository.class), roomMessages, new ChatContactMaskingService(), numericCounter, limiter);
    }

    private UUID user(RoleName role) {
        UUID id = UUID.randomUUID();
        sql.update("INSERT INTO users(id,full_name,email,password_hash) VALUES (?,'Synthetic user',?,'synthetic-hash')", id, id + "@example.invalid");
        sql.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name=?", id, role.name());
        sql.update("UPDATE role_verifications SET status='VERIFIED' WHERE user_id=?", id);
        return id;
    }

    private UUID message(boolean isRental, UUID sender) {
        return tx.execute(status -> isRental
                ? rentalMessages.saveAndFlush(RentalChatMessage.clean(rentalThread, sender, UUID.randomUUID(), "Safe masked text")).getId()
                : roomMessages.saveAndFlush(RoommateChatMessage.clean(roomThread, sender, UUID.randomUUID(), "Safe masked text")).getId());
    }

    private void ack(boolean isRental, UUID recipient, UUID thread, UUID... ids) {
        tx.executeWithoutResult(status -> {
            var request = new AcknowledgeChatMessagesRequest(thread, List.of(ids));
            if (isRental) rental.acknowledge(recipient, request); else roommate.acknowledge(recipient, request);
        });
    }

    private Instant delivered(boolean isRental, UUID message) {
        return tx.execute(status -> isRental ? rentalMessages.findById(message).orElseThrow().getDeliveredAt()
                : roomMessages.findById(message).orElseThrow().getDeliveredAt());
    }

    @Test void recipientReceiptsPersistAcrossHistoryAndReplayForBothDirectionsAndBothChats() {
        for (boolean isRental : List.of(true, false)) {
            UUID owner = isRental ? landlord : sublessor;
            UUID thread = isRental ? rentalThread : roomThread;
            for (UUID sender : List.of(renter, owner)) {
                UUID recipient = sender.equals(renter) ? owner : renter;
                UUID id = message(isRental, sender);
                assertNull(delivered(isRental, id));
                ack(isRental, recipient, thread, id);
                Instant first = delivered(isRental, id);
                assertNotNull(first);
                ack(isRental, recipient, thread, id, id);
                assertEquals(first, delivered(isRental, id));
                tx.executeWithoutResult(status -> {
                    if (isRental) assertEquals(first, rental.history(sender, thread, null, 100).items().stream()
                            .filter(value -> value.id().equals(id)).findFirst().orElseThrow().deliveredAt());
                    else assertEquals(first, roommate.history(sender, thread, null, 100).items().stream()
                            .filter(value -> value.id().equals(id)).findFirst().orElseThrow().deliveredAt());
                });
            }
        }
    }

    @Test void sendersOutsidersAndCrossThreadIdsCannotForgeDeliveryAndInvalidBatchesRollback() {
        for (boolean isRental : List.of(true, false)) {
            UUID owner = isRental ? landlord : sublessor;
            UUID thread = isRental ? rentalThread : roomThread;
            UUID id = message(isRental, renter);
            assertThrows(ResponseStatusException.class, () -> ack(isRental, renter, thread, id));
            assertThrows(ResponseStatusException.class, () -> ack(isRental, outsider, thread, id));
            assertThrows(ResponseStatusException.class, () -> ack(isRental, owner, isRental ? roomThread : rentalThread, id));
            assertNull(delivered(isRental, id));
            // A later invalid ID must roll back any earlier rows in the same batch.
            assertThrows(ResponseStatusException.class, () -> ack(isRental, owner, thread, id, new UUID(Long.MAX_VALUE, Long.MAX_VALUE)));
            assertNull(delivered(isRental, id));
            sql.update("UPDATE users SET account_status='SUSPENDED' WHERE id=?", owner);
            try { assertThrows(ResponseStatusException.class, () -> ack(isRental, owner, thread, id)); }
            finally { sql.update("UPDATE users SET account_status='ACTIVE' WHERE id=?", owner); }
            assertNull(delivered(isRental, id));
        }
    }

    @Test void concurrentRecipientAcknowledgementsPreserveOneFirstDeliveryTimestamp() throws Exception {
        for (boolean isRental : List.of(true, false)) {
            UUID id = message(isRental, renter);
            UUID owner = isRental ? landlord : sublessor;
            UUID thread = isRental ? rentalThread : roomThread;
            var start = new CountDownLatch(1);
            Callable<Instant> action = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                ack(isRental, owner, thread, id);
                return delivered(isRental, id);
            };
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(action); var second = executor.submit(action); start.countDown();
                assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            }
        }
    }

    @Test void changedRolesAndUnverifiedRentalParticipantsCannotAcknowledge() {
        UUID rentalId = message(true, renter);
        sql.update("UPDATE role_verifications SET status='NOT_STARTED' WHERE user_id=?", landlord);
        try { assertThrows(ResponseStatusException.class, () -> ack(true, landlord, rentalThread, rentalId)); }
        finally { sql.update("UPDATE role_verifications SET status='VERIFIED' WHERE user_id=?", landlord); }
        assertNull(delivered(true, rentalId));
        for (boolean isRental : List.of(true, false)) {
            UUID owner = isRental ? landlord : sublessor;
            UUID id = message(isRental, renter);
            sql.update("UPDATE user_roles SET role_id=(SELECT id FROM roles WHERE name='RENTER') WHERE user_id=?", owner);
            try { assertThrows(ResponseStatusException.class, () -> ack(isRental, owner, isRental ? rentalThread : roomThread, id)); }
            finally { sql.update("UPDATE user_roles SET role_id=(SELECT id FROM roles WHERE name=?) WHERE user_id=?", isRental ? "LANDLORD" : "SUBLESSOR", owner); }
            assertNull(delivered(isRental, id));
        }
    }

    @Test void numericGroupThresholdIsPerSenderAndChatAndRetriesDoNotCountTwice() {
        UUID rentalFirst = UUID.randomUUID();
        tx.executeWithoutResult(status -> rental.send(renter,
                new SendRentalChatMessageRequest(rentalThread, rentalFirst, "1 2 3 4 5")));
        tx.executeWithoutResult(status -> rental.send(renter,
                new SendRentalChatMessageRequest(rentalThread, rentalFirst, "1 2 3 4 5")));
        assertThrows(ResponseStatusException.class, () -> tx.executeWithoutResult(status -> rental.send(renter,
                new SendRentalChatMessageRequest(rentalThread, rentalFirst, "different text"))));
        assertThrows(ResponseStatusException.class, () -> tx.executeWithoutResult(status -> rental.send(outsider,
                new SendRentalChatMessageRequest(rentalThread, UUID.randomUUID(), "1 2 3 4 5 6"))));
        assertEquals(5, sql.queryForObject("SELECT group_count FROM rental_chat_numeric_counts WHERE chat_thread_id=? AND sender_id=?",
                Integer.class, rentalThread, renter));
        assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM rental_chat_numeric_counts WHERE sender_id=?",
                Integer.class, outsider));

        UUID rentalSixth = UUID.randomUUID();
        tx.executeWithoutResult(status -> rental.send(renter,
                new SendRentalChatMessageRequest(rentalThread, rentalSixth, "6")));
        RentalChatMessage flaggedRental = rentalMessages.findBySenderIdAndClientMessageId(renter, rentalSixth).orElseThrow();
        assertTrue(flaggedRental.isFlagged());
        assertEquals("[contact hidden]", flaggedRental.getMaskedBody());

        UUID landlordFirst = UUID.randomUUID();
        tx.executeWithoutResult(status -> rental.send(landlord,
                new SendRentalChatMessageRequest(rentalThread, landlordFirst, "1 2 3 4 5")));
        assertFalse(rentalMessages.findBySenderIdAndClientMessageId(landlord, landlordFirst).orElseThrow().isFlagged());

        UUID roomFirst = UUID.randomUUID();
        tx.executeWithoutResult(status -> roommate.send(renter,
                new SendRoommateChatMessageRequest(roomThread, roomFirst, "1 2 3 4 5")));
        UUID roomSixth = UUID.randomUUID();
        tx.executeWithoutResult(status -> roommate.send(renter,
                new SendRoommateChatMessageRequest(roomThread, roomSixth, "6")));
        RoommateChatMessage flaggedRoom = roomMessages.findBySenderIdAndClientMessageId(renter, roomSixth).orElseThrow();
        assertTrue(flaggedRoom.isFlagged());
        assertEquals("[contact hidden]", flaggedRoom.getMaskedBody());
    }

    @AfterAll void cleanup() {
        if (factory != null) factory.destroy();
        if (sql != null) sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
}
