package com.makaohub.backend.payment.service;

import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.verification.service.RoleVerificationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListingCardMetricsTest {
    @Test void filtersEveryQueryByOwnerAndCurrentRole() {
        var db = mock(JdbcTemplate.class);
        var service = new LandlordOfferService(db, mock(ListingRepository.class), mock(UserRepository.class), mock(RoleVerificationGuard.class));
        var owner = UUID.randomUUID();
        when(db.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            assertTrue(sql.contains("u.account_status='ACTIVE'"));
            assertTrue(sql.contains("WHERE l.sublessor_id=?"));
            assertTrue(sql.contains("g.sublessor_id=?"));
            assertTrue(sql.contains("r.name=?"));
            assertTrue(sql.contains("Africa/Nairobi"));
            assertEquals(owner, call.getArgument(2));
            assertEquals(owner, call.getArgument(3));
            assertEquals("SUBLESSOR", call.getArgument(4));
            return List.of();
        });
        assertTrue(service.cardMetrics(owner, true).isEmpty());
    }
}
