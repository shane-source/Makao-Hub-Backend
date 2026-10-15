package com.makaohub.backend.messaging.config;

import com.makaohub.backend.messaging.service.RoommatePresenceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class RoommatePresenceCleanupScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    RoommatePresenceCleanupScheduler.class
            );

    private static final String SCHEDULER_BEAN_NAME =
            "roommatePresenceCleanupTaskScheduler";

    private final RoommatePresenceRegistry presenceRegistry;

    public RoommatePresenceCleanupScheduler(
            RoommatePresenceRegistry presenceRegistry
    ) {
        this.presenceRegistry = presenceRegistry;
    }

    /**
     * Creates a dedicated scheduler for presence cleanup.
     *
     * It is intentionally separate from the WebSocket heartbeat
     * scheduler so JWT validation cannot delay heartbeat frames.
     */
    @Bean(name = SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler presenceCleanupTaskScheduler() {
        ThreadPoolTaskScheduler scheduler =
                new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(1);

        scheduler.setThreadNamePrefix(
                "roommate-presence-cleanup-"
        );

        scheduler.setRemoveOnCancelPolicy(true);

        scheduler.setWaitForTasksToCompleteOnShutdown(
                true
        );

        scheduler.setAwaitTerminationSeconds(5);

        return scheduler;
    }

    /**
     * Removes expired, revoked, or otherwise invalid WebSocket
     * sessions from the presence registry.
     *
     * RECOMMENDED default: run every 30 seconds.
     */
    @Scheduled(
            fixedDelayString =
                    "${WEBSOCKET_PRESENCE_CLEANUP_DELAY_MS:30000}",
            initialDelayString =
                    "${WEBSOCKET_PRESENCE_CLEANUP_DELAY_MS:30000}",
            scheduler = SCHEDULER_BEAN_NAME
    )
    public void purgeInvalidSessions() {
        try {
            presenceRegistry.purgeInvalidSessions();
        } catch (RuntimeException exception) {
            /*
             * Do not log the exception message because failures from
             * authentication components could contain sensitive data.
             *
             * A later scheduled execution will retry automatically.
             */
            LOGGER.error(
                    "Roommate presence cleanup failed; "
                            + "the next scheduled run will retry."
            );
        }
    }
}