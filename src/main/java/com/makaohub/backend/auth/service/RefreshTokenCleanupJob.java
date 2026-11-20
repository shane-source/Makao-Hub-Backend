package com.makaohub.backend.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Removes refresh tokens that can no longer be used. Used-but-unexpired tokens are kept on purpose,
 * because replaying them is how token theft is detected.
 */
@Component
@EnableScheduling
public class RefreshTokenCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);
    private static final Duration GRACE = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RefreshTokenCleanupJob(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "${makao.jwt.cleanup-cron:0 30 3 * * *}")
    public void purge() {
        Timestamp cutoff = Timestamp.from(Instant.now(clock).minus(GRACE));
        int removed = jdbc.update(
                "DELETE FROM refresh_tokens WHERE expires_at < ? OR revoked_at < ?", cutoff, cutoff);
        if (removed > 0) log.info("REFRESH_TOKEN_CLEANUP removed={}", removed);
    }
}
