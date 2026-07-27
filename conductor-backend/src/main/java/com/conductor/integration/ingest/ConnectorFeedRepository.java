package com.conductor.integration.ingest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConnectorFeedRepository extends JpaRepository<ConnectorFeed, String> {

    Optional<ConnectorFeed> findByConnectionIdAndIngestId(String connectionId, String ingestId);

    List<ConnectorFeed> findByProjectId(String projectId);

    List<ConnectorFeed> findByConnectionId(String connectionId);

    List<ConnectorFeed> findByProjectIdAndConnectorId(String projectId, String connectorId);

    /**
     * Oldest-first batch (up to {@code limit}) of due, enabled, ACTIVE feeds, row-locked so two
     * concurrent scheduler instances can never claim the same feed -- {@code FOR UPDATE SKIP LOCKED}
     * isn't expressible in JPQL, so this is native, mirroring
     * {@code KnowledgeSourceRepository#findDuePendingForProjectAndDomain} and
     * {@code WorkflowJobQueueRepository#claimNextJob}. Caller must advance {@code next_run_at} (or
     * flip {@code status}) in the same transaction that ran this query, before the row locks release.
     */
    @Query(value = "SELECT * FROM connector_feed WHERE status = 'ACTIVE' AND enabled = true "
            + "AND next_run_at <= :now ORDER BY next_run_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<ConnectorFeed> claimDue(@Param("now") OffsetDateTime now, @Param("limit") int limit);
}
