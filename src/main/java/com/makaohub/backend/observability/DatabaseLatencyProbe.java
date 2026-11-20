package com.makaohub.backend.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** At startup, times a trivial query so a slow database link shows up immediately in the log. */
@Component
public class DatabaseLatencyProbe {

    private static final Logger log = LoggerFactory.getLogger("makao.config");

    private final JdbcTemplate jdbc;

    public DatabaseLatencyProbe(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @EventListener(ApplicationReadyEvent.class)
    public void probe() {
        try {
            jdbc.queryForObject("select 1", Integer.class); // warm the connection first
            long total = 0;
            for (int i = 0; i < 5; i++) {
                long t = System.nanoTime();
                jdbc.queryForObject("select 1", Integer.class);
                total += System.nanoTime() - t;
            }
            long avg = total / 5 / 1_000_000;
            if (avg >= 80) {
                log.warn("DB_LATENCY avg={}ms per query. Each API call makes several queries, so this makes every screen slow. "
                        + "Use a Supabase pooler/region close to the backend (and run the backend near Kenya/Europe, not far from the database).", avg);
            } else {
                log.info("DB_LATENCY avg={}ms per query (fine)", avg);
            }
        } catch (RuntimeException exception) {
            log.error("DB_LATENCY probe failed: {}", exception.toString());
        }
    }
}
