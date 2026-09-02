package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.repository.ConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The single writer of a connection's health — the system's verdict on whether the platform still
 * accepts that connection's credentials.
 *
 * <p>Health is deliberately <b>not</b> {@code Connection.status}. Status is the user's intent
 * ("I want this connected"); health is what the platform told us last time we used it. An expired
 * or revoked token therefore leaves the connection {@code ACTIVE} and merely marks it
 * {@code UNHEALTHY}, so the Integrations UI can show it needs reconnecting instead of the failure
 * surfacing only as a publish that silently didn't happen. Nothing here ever deletes, disables, or
 * otherwise touches status.
 *
 * <p>Health tracking is a side-channel on top of whatever operation discovered the problem, so
 * neither method throws: a connection that has since been deleted is logged and skipped rather than
 * turned into a second failure on top of the first.
 */
@Service
public class ConnectionHealthService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHealthService.class);

    public static final String HEALTHY = "HEALTHY";
    public static final String UNHEALTHY = "UNHEALTHY";

    /** Provider error bodies can be arbitrarily long; the row stores enough to be actionable. */
    public static final int MAX_MESSAGE_LENGTH = 500;

    private static final String DEFAULT_REASON =
            "The platform rejected this connection's credentials. Reconnect the account.";

    private final ConnectionRepository connectionRepository;

    public ConnectionHealthService(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
    }

    /**
     * Records that the platform has permanently rejected this connection's credentials.
     *
     * @param reason the platform's own words, shown to the human who has to fix it; a blank or null
     *               reason falls back to a generic but still actionable message
     */
    @Transactional
    public void markUnhealthy(String connectionId, String reason) {
        find(connectionId, UNHEALTHY).ifPresent(conn -> {
            conn.setHealthStatus(UNHEALTHY);
            conn.setHealthCheckedAt(OffsetDateTime.now());
            conn.setHealthMessage(readableReason(reason));
            connectionRepository.save(conn);
            log.warn("Connection {} ({}) marked UNHEALTHY: {}",
                    conn.getId(), conn.getConnectorId(), conn.getHealthMessage());
        });
    }

    /** Records that the platform accepted this connection's credentials, clearing any prior reason. */
    @Transactional
    public void markHealthy(String connectionId) {
        find(connectionId, HEALTHY).ifPresent(conn -> {
            boolean recovered = UNHEALTHY.equals(conn.getHealthStatus());
            conn.setHealthStatus(HEALTHY);
            conn.setHealthCheckedAt(OffsetDateTime.now());
            conn.setHealthMessage(null);
            connectionRepository.save(conn);
            if (recovered) {
                log.info("Connection {} ({}) recovered to HEALTHY", conn.getId(), conn.getConnectorId());
            }
        });
    }

    /**
     * Entry point for the publish path: a post could not be delivered because the platform rejected
     * our identity or our permissions (an expired token, a revoked grant, a missing scope) rather
     * than for any transient reason. Callers must only use this for <b>permanent</b> auth/permission
     * failures — a rate limit or a 5xx is not one, and must not cost the connection its health.
     */
    @Transactional
    public void reportPublishAuthFailure(String connectionId, String reason) {
        markUnhealthy(connectionId, reason);
    }

    private Optional<Connection> find(String connectionId, String intendedStatus) {
        Optional<Connection> conn = connectionRepository.findById(connectionId);
        if (conn.isEmpty()) {
            log.warn("Cannot mark connection {} {} — no such connection", connectionId, intendedStatus);
        }
        return conn;
    }

    private static String readableReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_REASON;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= MAX_MESSAGE_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }
}
