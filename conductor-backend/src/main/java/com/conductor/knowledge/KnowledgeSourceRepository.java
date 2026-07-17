package com.conductor.knowledge;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    /** Distinct projects with at least one PENDING source due for (re)processing -- {@code KnowledgeIngestScheduler}'s dispatch fan-out. */
    @Query("SELECT DISTINCT s.projectId FROM KnowledgeSource s "
            + "WHERE s.status = com.conductor.knowledge.KnowledgeSourceStatus.PENDING "
            + "AND (s.nextAttemptAt IS NULL OR s.nextAttemptAt <= :now)")
    List<String> findProjectIdsWithDuePending(@Param("now") OffsetDateTime now);

    /**
     * The oldest-first batch (up to {@code limit}) of a project's due PENDING sources, row-locked so two
     * concurrent {@code KnowledgeIngestScheduler} instances can never claim the same source --
     * {@code FOR UPDATE SKIP LOCKED} isn't expressible in JPQL, so this is native (see
     * {@code WorkflowJobQueueRepository} for the codebase's other use of this pattern). Caller must flip
     * the returned rows to PROCESSING in the same transaction that ran this query, before the row locks
     * release.
     */
    @Query(value = "SELECT * FROM knowledge_sources WHERE project_id = :projectId AND status = 'PENDING' "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= :now) "
            + "ORDER BY received_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<KnowledgeSource> findDuePendingForProject(@Param("projectId") String projectId,
                                                    @Param("now") OffsetDateTime now, @Param("limit") int limit);

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
}
