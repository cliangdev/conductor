package com.conductor.integration.ingest;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConnectorFeedDigestRepository extends JpaRepository<ConnectorFeedDigest, String> {

    Optional<ConnectorFeedDigest> findByFeedIdAndPeriodKey(String feedId, String periodKey);

    List<ConnectorFeedDigest> findByFeedId(String feedId);

    List<ConnectorFeedDigest> findByStatus(DigestStatus status);

    /**
     * Oldest-first, bounded page of digests in one status. The sweep uses this rather than
     * {@link #findByStatus} so its per-tick cost stays bounded like the two claim queries beside it:
     * an unbounded scan plus a per-row workflow-run lookup would grow with backlog size exactly when
     * the system is already struggling (narration stalling is what creates the backlog). Anything not
     * reached this tick is picked up on the next one, 60s later.
     */
    List<ConnectorFeedDigest> findByStatusOrderByCreatedAtAsc(DigestStatus status, Pageable pageable);

    /**
     * Oldest-first batch (up to {@code limit}) of due, PENDING digests, row-locked so two concurrent
     * scheduler instances can never claim the same digest -- same {@code FOR UPDATE SKIP LOCKED}
     * shape as {@code ConnectorFeedRepository#claimDue}. Caller must flip {@code status} to NARRATING
     * in the same transaction that ran this query, before the row locks release.
     */
    @Query(value = "SELECT * FROM connector_feed_digest WHERE status = 'PENDING' "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= :now) "
            + "ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<ConnectorFeedDigest> claimDuePending(@Param("now") OffsetDateTime now, @Param("limit") int limit);
}
