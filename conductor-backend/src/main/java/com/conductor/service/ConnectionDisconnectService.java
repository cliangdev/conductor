package com.conductor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one sequence that removes a connection, so the disconnect button and an OAuth re-authorization
 * that collapses a duplicate row cannot drift apart. Everything that references a connection without
 * cascading has to let go of it first: publish destinations (a Post still waiting on the account refuses
 * the removal by name) and runtime targets (the FK is {@code ON DELETE SET NULL}, so they must be told
 * before the row vanishes).
 */
@Service
public class ConnectionDisconnectService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionDisconnectService.class);

    private final ConnectionService connectionService;
    private final PublishTargetService publishTargetService;
    private final RuntimeTargetService runtimeTargetService;
    private final OAuthRevocationService revocationService;

    public ConnectionDisconnectService(ConnectionService connectionService,
                                       PublishTargetService publishTargetService,
                                       RuntimeTargetService runtimeTargetService,
                                       OAuthRevocationService revocationService) {
        this.connectionService = connectionService;
        this.publishTargetService = publishTargetService;
        this.runtimeTargetService = runtimeTargetService;
        this.revocationService = revocationService;
    }

    /**
     * Disconnects a connection on a member's request. Publish destinations first: a Post still waiting on
     * this account refuses the disconnect by name (409), and settled ones let go of the row so the FK does
     * not turn this into a 500. Then runtime targets, before the row goes away: referencing targets flip
     * to ERROR and their cached Cloud Run clients are closed.
     *
     * <p>Finally the provider-side grant is revoked where the connector supports it (YouTube), read
     * before the row goes and sent after commit; see {@link OAuthRevocationService}.
     */
    @Transactional
    public void disconnect(String connectionId) {
        publishTargetService.detachFromConnection(connectionId);
        runtimeTargetService.onConnectionDeleted(connectionId);
        connectionService.getById(connectionId).ifPresent(revocationService::revokeAfterCommit);
        connectionService.delete(connectionId);
    }

    /**
     * Folds {@code duplicateId} into {@code survivorId}, two rows for one external account: the
     * duplicate's settled destinations move to the survivor (history and metrics stay on a live
     * connection) and the duplicate is removed through the same sequence as {@link #disconnect}.
     *
     * <p>Best-effort and never throws for a business reason: a duplicate with a Post still waiting on it
     * is kept and logged, because silently stranding a scheduled Post is worse than showing two rows.
     * The question is asked up front rather than caught, since a refusal thrown through a
     * {@code @Transactional} proxy would mark the caller's transaction rollback-only.
     *
     * <p>Never revokes the provider grant: the survivor holds a token from the same grant, and revoking
     * would kill it too.
     *
     * @return whether the duplicate was removed
     */
    @Transactional
    public boolean mergeDuplicate(String duplicateId, String survivorId) {
        if (publishTargetService.hasTargetsStillToPublish(duplicateId)) {
            log.warn("Keeping duplicate connection={} of connection={}: a Post is still waiting on it",
                    duplicateId, survivorId);
            return false;
        }
        publishTargetService.repointSettledTargets(duplicateId, survivorId);
        runtimeTargetService.onConnectionDeleted(duplicateId);
        connectionService.delete(duplicateId);
        log.info("Merged duplicate connection={} into connection={}", duplicateId, survivorId);
        return true;
    }
}
