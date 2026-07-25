package com.conductor.knowledge;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeSourceRepository extends JpaRepository<KnowledgeSource, String> {

    Optional<KnowledgeSource> findByProjectIdAndDedupKey(String projectId, String dedupKey);

    List<KnowledgeSource> findByProjectIdAndIdIn(String projectId, Collection<String> ids);

    List<KnowledgeSource> findByProjectIdAndStatusOrderByReceivedAtDesc(String projectId, KnowledgeSourceStatus status);

    List<KnowledgeSource> findByStatus(KnowledgeSourceStatus status);

    /** Per-status row counts for a project's inbox -- {@code KnowledgeIngestionService#getSourceCounts}'s
     *  backing query. Statuses with zero rows are simply absent from the result; the service fills in
     *  zero-defaults. */
    @Query("SELECT s.status, COUNT(s) FROM KnowledgeSource s WHERE s.projectId = :projectId GROUP BY s.status")
    List<Object[]> countByProjectIdGroupByStatus(@Param("projectId") String projectId);

    /** Distinct projects with at least one PENDING source due for (re)processing -- {@code KnowledgeIngestScheduler}'s dispatch fan-out. */
    @Query("SELECT DISTINCT s.projectId FROM KnowledgeSource s "
            + "WHERE s.status = com.conductor.knowledge.KnowledgeSourceStatus.PENDING "
            + "AND (s.nextAttemptAt IS NULL OR s.nextAttemptAt <= :now)")
    List<String> findProjectIdsWithDuePending(@Param("now") OffsetDateTime now);

    // ---- domain-aware lanes (KnowledgeIngestScheduler concurrency unit is per (project, domain)) ----

    /** Distinct domain lanes (including {@code null}, the generalist lane) with at least one due PENDING
     *  source in this project -- the scheduler dispatches each lane independently, in the same tick. */
    @Query("SELECT DISTINCT s.domain FROM KnowledgeSource s WHERE s.projectId = :projectId "
            + "AND s.status = com.conductor.knowledge.KnowledgeSourceStatus.PENDING "
            + "AND (s.nextAttemptAt IS NULL OR s.nextAttemptAt <= :now)")
    List<String> findLanesWithDuePending(@Param("projectId") String projectId, @Param("now") OffsetDateTime now);

    /**
     * The oldest-first batch (up to {@code limit}) of a project's due PENDING sources in one lane,
     * row-locked so two concurrent {@code KnowledgeIngestScheduler} instances can never claim the same
     * source -- {@code FOR UPDATE SKIP LOCKED} isn't expressible in JPQL, so this is native (see
     * {@code WorkflowJobQueueRepository} for the codebase's other use of this pattern). Caller must flip
     * the returned rows to PROCESSING in the same transaction that ran this query, before the row locks
     * release. {@code domain IS NOT DISTINCT FROM} (null-safe equality; a plain {@code =} never matches
     * a null bind parameter in Postgres) so the null lane's own claim only ever picks up null-domain
     * rows, never another lane's.
     */
    @Query(value = "SELECT * FROM knowledge_sources WHERE project_id = :projectId AND status = 'PENDING' "
            + "AND domain IS NOT DISTINCT FROM :domain "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= :now) "
            + "ORDER BY received_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<KnowledgeSource> findDuePendingForProjectAndDomain(@Param("projectId") String projectId,
                                                             @Param("domain") String domain,
                                                             @Param("now") OffsetDateTime now, @Param("limit") int limit);

    /** True if this lane (domain, or the null lane) has any source currently PROCESSING -- the
     *  scheduler's per-lane busy check; a busy lane is skipped this tick, other lanes are unaffected. */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM knowledge_sources WHERE project_id = :projectId "
            + "AND status = 'PROCESSING' AND domain IS NOT DISTINCT FROM :domain)", nativeQuery = true)
    boolean existsProcessingInLane(@Param("projectId") String projectId, @Param("domain") String domain);

    /** True if this lane already has a PENDING or PROCESSING source -- {@code KnowledgeIngestionService}'s
     *  idle-lane check: only a source landing in a truly idle lane gets its dispatch stamped out to the
     *  project's configured ingest interval; anything ingested while the lane's already accumulating
     *  rides along instead (see {@link #findEarliestPendingNextAttemptInLane}). */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM knowledge_sources WHERE project_id = :projectId "
            + "AND status IN ('PENDING', 'PROCESSING') AND domain IS NOT DISTINCT FROM :domain)", nativeQuery = true)
    boolean existsPendingOrProcessingInLane(@Param("projectId") String projectId, @Param("domain") String domain);

    /** Earliest scheduled dispatch time among this lane's PENDING sources, if any -- lets a source
     *  ingested into a lane that's already accumulating (but hasn't dispatched yet) inherit the same
     *  scheduled time instead of getting its own, so both fire together. {@code Instant}, not
     *  {@code OffsetDateTime}: a native scalar aggregate over a {@code timestamptz} column maps to
     *  {@code Instant} here, unlike the entity-mapped {@code OffsetDateTime} field itself. */
    @Query(value = "SELECT MIN(next_attempt_at) FROM knowledge_sources WHERE project_id = :projectId "
            + "AND status = 'PENDING' AND domain IS NOT DISTINCT FROM :domain", nativeQuery = true)
    Instant findEarliestPendingNextAttemptInLane(@Param("projectId") String projectId, @Param("domain") String domain);

    /** Per-(domain, status) row counts for a project -- backs the Domains panel's pending/processing/
     *  processed counts per {@code KnowledgeDomain}. Domains (and the status) with zero rows are simply
     *  absent, same zero-fill-by-caller convention as {@link #countByProjectIdGroupByStatus}. */
    @Query("SELECT s.domain, s.status, COUNT(s) FROM KnowledgeSource s WHERE s.projectId = :projectId "
            + "GROUP BY s.domain, s.status")
    List<Object[]> countByProjectIdGroupByDomainAndStatus(@Param("projectId") String projectId);

    List<KnowledgeSource> findByProjectIdAndStatusAndDomainOrderByReceivedAtDesc(
            String projectId, KnowledgeSourceStatus status, String domain);

    /**
     * Marks a batch of sources PROCESSED as part of the same transaction that wrote the pages derived
     * from them -- see {@code KnowledgePageService#batchWrite} -- so a crash between the page write and
     * this update can never leave a source silently re-processed or silently dropped. Guarded to only
     * move sources out of PENDING/PROCESSING: a claimed source is PROCESSING, but a bootstrap-flow source
     * (submitted then written about without ever being claimed by the scheduler -- see
     * {@code knowledge-bootstrap.yaml}) is still PENDING, so both must be eligible; DEAD/already-PROCESSED
     * rows must never be silently overwritten.
     * {@code clearAutomatically}: a bulk update bypasses the persistence context, so without it a
     * {@link KnowledgeSource} already loaded earlier in the same transaction would keep reporting its
     * stale pre-update status.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE KnowledgeSource s SET s.status = com.conductor.knowledge.KnowledgeSourceStatus.PROCESSED "
            + "WHERE s.projectId = :projectId AND s.id IN :ids "
            + "AND s.status IN (com.conductor.knowledge.KnowledgeSourceStatus.PENDING, "
            + "com.conductor.knowledge.KnowledgeSourceStatus.PROCESSING)")
    int markProcessed(@Param("projectId") String projectId, @Param("ids") Collection<String> ids);

    // ---- retention (KnowledgeRetentionService) ----

    /** Oldest-first batch of sources old enough for their retention action and not yet purged --
     *  PROCESSED rows to compact, DEAD rows to delete (or tombstone, if provenance references them). */
    List<KnowledgeSource> findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
            KnowledgeSourceStatus status, OffsetDateTime cutoff, Pageable pageable);

    /**
     * True if any page revision's provenance links to this source ({@code knowledge_revision_sources}).
     * Guards the DEAD hard-delete: a wedged librarian run can link a revision to a source *after* the
     * stale sweep dead-lettered it ({@code markProcessed} only moves PENDING/PROCESSING rows, so the
     * status stays DEAD) -- and the join table's {@code ON DELETE CASCADE} would silently erase that
     * provenance if the row were deleted. Native: the join table has no entity.
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM knowledge_revision_sources WHERE source_id = :sourceId)",
            nativeQuery = true)
    boolean isReferencedByRevision(@Param("sourceId") String sourceId);

    /**
     * Resets every DEAD source in a project back to PENDING (attempts/nextAttemptAt/errorMessage
     * cleared) in one bulk update -- {@code KnowledgeIngestionService#retryDeadSources}'s backing
     * query, an ops recovery action for {@code KnowledgeController#retryDeadKnowledgeSources}.
     * {@code clearAutomatically}: same persistence-context staleness hazard as {@link #markProcessed}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE KnowledgeSource s SET s.status = com.conductor.knowledge.KnowledgeSourceStatus.PENDING, "
            + "s.attempts = 0, s.nextAttemptAt = NULL, s.errorMessage = NULL "
            + "WHERE s.projectId = :projectId AND s.status = com.conductor.knowledge.KnowledgeSourceStatus.DEAD")
    int retryDeadSources(@Param("projectId") String projectId);
}
