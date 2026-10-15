package com.makaohub.backend.messaging.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class RoommatePresenceRegistry {

    /*
     * Both indexes must always be changed while holding this lock.
     */
    private final Object monitor = new Object();

    /*
     * Finds the owner of a WebSocket session in average O(1) time.
     */
    private final Map<String, UUID> userBySession =
            new HashMap<>();

    /*
     * Finds all sessions belonging to one user in average O(1) time.
     */
    private final Map<UUID, Map<String, AuthenticatedSession>>
            sessionsByUser = new HashMap<>();

    private final JwtDecoder jwtDecoder;
    private final ApplicationEventPublisher events;

    public RoommatePresenceRegistry(
            JwtDecoder jwtDecoder,
            ApplicationEventPublisher events
    ) {
        this.jwtDecoder = jwtDecoder;
        this.events = events;
    }

    /**
     * Records a connection after Spring accepts STOMP CONNECT.
     */
    @EventListener
    public void onConnected(
            SessionConnectedEvent event
    ) {
        if (!(event.getUser()
                instanceof JwtAuthenticationToken authentication)
                || !authentication.isAuthenticated()) {
            return;
        }

        String sessionId =
                SimpMessageHeaderAccessor.getSessionId(
                        event.getMessage().getHeaders()
                );

        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        Jwt jwt = authentication.getToken();
        UUID userId;

        try {
            userId = UUID.fromString(jwt.getSubject());

            /*
             * The authenticated principal and JWT subject must refer
             * to the same account.
             */
            if (!jwt.getSubject().equals(
                    authentication.getName()
            )) {
                return;
            }
        } catch (
                IllegalArgumentException
                | NullPointerException exception
        ) {
            return;
        }

        synchronized (monitor) {
            /*
             * A repeated event cannot replace an existing session's
             * owner or original credential.
             */
            if (userBySession.containsKey(sessionId)) {
                return;
            }

            userBySession.put(sessionId, userId);

            sessionsByUser
                    .computeIfAbsent(
                            userId,
                            ignored -> new HashMap<>()
                    )
                    .put(
                            sessionId,
                            new AuthenticatedSession(
                                    sessionId,
                                    jwt
                            )
                    );
        }

        /*
         * Presence listeners may query the database, so the event
         * must be published after releasing the registry lock.
         */
        events.publishEvent(new PresenceChanged(userId));
    }

    /**
     * Removes exactly the WebSocket connection that ended.
     *
     * Repeated disconnect events have no additional effect.
     */
    @EventListener
    public void onDisconnected(
            SessionDisconnectEvent event
    ) {
        String sessionId = event.getSessionId();

        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        UUID userId;

        synchronized (monitor) {
            userId = removeSession(sessionId);
        }

        if (userId != null) {
            events.publishEvent(
                    new PresenceChanged(userId)
            );
        }
    }

    /**
     * Removes registered sessions whose original JWT is now expired,
     * revoked, invalid, or belongs to a different account.
     *
     * The returned value contains only the number of removed sessions.
     * It does not expose account IDs, session IDs, or credentials.
     */
    public int purgeInvalidSessions() {
        /*
         * JWT validation is performed against a snapshot so the main
         * registry lock is not held during cryptographic validation.
         */
        List<OwnedSession> snapshot =
                allSessionsSnapshot();

        /*
         * A HashSet ensures that a user with several expired sockets
         * produces only one presence-change event.
         */
        Set<UUID> changedUsers = new HashSet<>();

        int removed = 0;

        for (OwnedSession candidate : snapshot) {
            if (isValid(
                    candidate.userId(),
                    candidate.session()
            )) {
                continue;
            }

            boolean removedCurrentSession;

            synchronized (monitor) {
                Map<String, AuthenticatedSession> currentSessions =
                        sessionsByUser.get(
                                candidate.userId()
                        );

                /*
                 * Identity comparison prevents an old snapshot from
                 * deleting a newer session that reused the same ID.
                 */
                removedCurrentSession =
                        currentSessions != null
                                && currentSessions.get(
                                candidate.session()
                                        .sessionId()
                        ) == candidate.session();

                if (removedCurrentSession) {
                    removeSession(
                            candidate.session().sessionId()
                    );
                }
            }

            if (removedCurrentSession) {
                removed++;
                changedUsers.add(candidate.userId());
            }
        }

        /*
         * Event handlers may access the database and must never run
         * while the registry lock is held.
         */
        for (UUID userId : changedUsers) {
            events.publishEvent(
                    new PresenceChanged(userId)
            );
        }

        return removed;
    }

    /**
     * Returns true only when the user has at least one registered
     * session with a JWT that remains valid.
     */
    public boolean isOnline(
            UUID userId
    ) {
        if (userId == null) {
            return false;
        }

        for (AuthenticatedSession session
                : sessionsFor(userId)) {
            if (!isValid(userId, session)) {
                continue;
            }

            /*
             * The session could disconnect or be cleaned up while its
             * JWT is being validated, so confirm it still exists.
             */
            synchronized (monitor) {
                Map<String, AuthenticatedSession> current =
                        sessionsByUser.get(userId);

                if (current != null
                        && current.get(
                        session.sessionId()
                ) == session) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Returns the number of physically registered sockets.
     *
     * This is not necessarily the number of sessions with valid JWTs.
     */
    public int connectionCount(
            UUID userId
    ) {
        synchronized (monitor) {
            Map<String, AuthenticatedSession> sessions =
                    sessionsByUser.get(userId);

            return sessions == null
                    ? 0
                    : sessions.size();
        }
    }

    /**
     * Returns an immutable snapshot for internal message and presence
     * publishers.
     *
     * Package-private prevents controllers and unrelated modules from
     * treating this as a public presence API.
     */
    List<AuthenticatedSession> sessionsFor(
            UUID userId
    ) {
        synchronized (monitor) {
            Map<String, AuthenticatedSession> sessions =
                    sessionsByUser.get(userId);

            return sessions == null
                    ? List.of()
                    : List.copyOf(sessions.values());
        }
    }

    /**
     * Copies all registered sessions while briefly holding the lock.
     *
     * JWT decoding happens after this method releases the lock.
     */
    private List<OwnedSession> allSessionsSnapshot() {
        synchronized (monitor) {
            List<OwnedSession> snapshot =
                    new ArrayList<>(
                            userBySession.size()
                    );

            for (Map.Entry<
                    UUID,
                    Map<String, AuthenticatedSession>
                    > entry : sessionsByUser.entrySet()) {

                for (AuthenticatedSession session
                        : entry.getValue().values()) {
                    snapshot.add(
                            new OwnedSession(
                                    entry.getKey(),
                                    session
                            )
                    );
                }
            }

            return snapshot;
        }
    }

    /**
     * Revalidates the JWT supplied when the WebSocket connected.
     *
     * The configured JwtDecoder checks the signature, expiration,
     * issuer, audience, and current authorization version.
     */
    private boolean isValid(
            UUID expectedUserId,
            AuthenticatedSession session
    ) {
        try {
            Jwt verified = jwtDecoder.decode(
                    session.jwt().getTokenValue()
            );

            /*
             * A valid token must still belong to the expected account.
             */
            return expectedUserId
                    .toString()
                    .equals(verified.getSubject());
        } catch (
                JwtException
                | IllegalArgumentException
                | NullPointerException exception
        ) {
            /*
             * Normal invalid-token failures are treated as offline.
             * No token details are written to logs.
             */
            return false;
        }
    }

    /**
     * Removes one session from both registry indexes.
     *
     * The caller must hold the monitor lock.
     */
    private UUID removeSession(
            String sessionId
    ) {
        UUID userId =
                userBySession.remove(sessionId);

        if (userId == null) {
            return null;
        }

        Map<String, AuthenticatedSession> sessions =
                sessionsByUser.get(userId);

        if (sessions != null) {
            sessions.remove(sessionId);

            if (sessions.isEmpty()) {
                sessionsByUser.remove(userId);
            }
        }

        return userId;
    }

    /**
     * Stores the connection ID and original JWT internally.
     *
     * Never return this record from an HTTP or WebSocket response.
     */
    record AuthenticatedSession(
            String sessionId,
            Jwt jwt
    ) {
        /**
         * Prevents accidental credential exposure in logs.
         */
        @Override
        public String toString() {
            return "AuthenticatedSession[redacted]";
        }
    }

    /**
     * Immutable entry used only during cleanup.
     */
    private record OwnedSession(
            UUID userId,
            AuthenticatedSession session
    ) {
    }

    /**
     * Internal event telling listeners to recompute the user's current
     * authorized presence.
     */
    public record PresenceChanged(
            UUID userId
    ) {
    }
}