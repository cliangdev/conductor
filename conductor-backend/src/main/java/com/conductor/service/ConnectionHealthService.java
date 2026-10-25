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
    /** Status of a connection whose feed pulls fine but whose data has stopped advancing; see {@link #markNoNewData}. */
    public static final String DEGRADED = "DEGRADED";
    /** {@code health_detail} marker identifying a DEGRADED written by {@link #markNoNewData}, so only that flag is ever cleared. */
    public static final String NO_NEW_DATA_DETAIL = "NO_NEW_DATA";

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
            conn.setHealthDetail(cap(PlatformErrorText.detail(reason)));
            connectionRepository.save(conn);
            log.warn("Connection {} ({}) marked UNHEALTHY: {}",
                    conn.getId(), conn.getConnectorId(), conn.getHealthMessage());
        });
    }

    /** Records that the platform accepted this connection's credentials, clearing any prior reason. */
    @Transactional
    public void markHealthy(String connectionId) {
        find(connectionId, HEALTHY).ifPresent(conn -> {
            if (isNoNewData(conn)) {
                // Credentials work, but that says nothing about the data having stopped: not ours to clear.
                return;
            }
            boolean recovered = UNHEALTHY.equals(conn.getHealthStatus());
            conn.setHealthStatus(HEALTHY);
            conn.setHealthCheckedAt(OffsetDateTime.now());
            conn.setHealthMessage(null);
            conn.setHealthDetail(null);
            connectionRepository.save(conn);
            if (recovered) {
                log.info("Connection {} ({}) recovered to HEALTHY", conn.getId(), conn.getConnectorId());
            }
        });
    }

    /**
     * Flags a connection whose feed keeps pulling successfully but whose newest stored day is {@code since}.
     * Purely informational (DEGRADED + message): it never touches the feed, and it never overwrites a more
     * severe verdict (UNHEALTHY / SETUP_REQUIRED) from an actual auth or setup failure.
     */
    @Transactional
    public void markNoNewData(String connectionId, java.time.LocalDate since) {
        String message = "No new data since "
                + java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH).format(since);
        // One conditional UPDATE: a concurrent markUnhealthy can't be overwritten by a read-then-save.
        connectionRepository.markNoNewData(connectionId, message);
    }

    /** Clears the flag {@link #markNoNewData} set (and only that flag) once newer data arrives. */
    @Transactional
    public void clearNoNewData(String connectionId) {
        connectionRepository.clearNoNewData(connectionId);
    }

    private static boolean isNoNewData(Connection conn) {
        return DEGRADED.equals(conn.getHealthStatus()) && NO_NEW_DATA_DETAIL.equals(conn.getHealthDetail());
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
        String humanized = PlatformErrorText.humanize(reason);
        return humanized == null ? DEFAULT_REASON : cap(humanized);
    }

    private static String cap(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }
}
