package com.makaohub.backend.messaging.config;

import com.makaohub.backend.messaging.service.RoommatePresenceRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RoommatePresenceCleanupSchedulerTest {

    @Test
    void scheduledExecutionInvokesRegistryCleanup() {
        FakePresenceRegistry registry =
                new FakePresenceRegistry(false);

        RoommatePresenceCleanupScheduler scheduler =
                new RoommatePresenceCleanupScheduler(
                        registry
                );

        scheduler.purgeInvalidSessions();

        assertEquals(
                1,
                registry.invocationCount()
        );
    }

    @Test
    void cleanupFailureDoesNotEscapeScheduledMethod() {
        FakePresenceRegistry registry =
                new FakePresenceRegistry(true);

        RoommatePresenceCleanupScheduler scheduler =
                new RoommatePresenceCleanupScheduler(
                        registry
                );

        /*
         * A temporary cleanup failure must not escape the scheduled
         * method and terminate future cleanup executions.
         */
        assertDoesNotThrow(
                scheduler::purgeInvalidSessions
        );

        assertEquals(
                1,
                registry.invocationCount()
        );
    }

    /**
     * Minimal registry replacement used to test only scheduler
     * orchestration.
     *
     * It avoids Mockito, a database, WebSockets and JWT cryptography.
     */
    private static final class FakePresenceRegistry
            extends RoommatePresenceRegistry {

        private final boolean fail;
        private int invocationCount;

        private FakePresenceRegistry(
                boolean fail
        ) {
            super(
                    token -> {
                        throw new UnsupportedOperationException(
                                "JWT decoding is not used by this test."
                        );
                    },
                    ignored -> {
                    }
            );

            this.fail = fail;
        }

        /**
         * Records each call and optionally simulates a temporary
         * registry failure.
         */
        @Override
        public int purgeInvalidSessions() {
            invocationCount++;

            if (fail) {
                throw new IllegalStateException(
                        "Simulated cleanup failure."
                );
            }

            return 0;
        }

        /**
         * Returns how many times the scheduler invoked cleanup.
         */
        private int invocationCount() {
            return invocationCount;
        }
    }
}