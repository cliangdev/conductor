package com.conductor.knowledge;

import com.conductor.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Hourly retention sweep for the ingestion inbox ({@code knowledge_sources}). Once a source's payload
 * has served its purpose, keeping the raw content around indefinitely is pure bloat -- the compounded
 * wiki pages are the durable record, not the inbox. Three independent sweeps, each bounded to one batch
 * per tick so a large backlog never holds a long transaction or spikes load:
 *
 * <ul>
 *   <li><b>Compact</b> -- PROCESSED sources older than {@link #compactAfterDays} (default 30): null out
 *       the inline {@code payload} column and delete any offloaded GCS object, then stamp
 *       {@code purgedAt}. The row itself (id, type, ref, metadata, status, timestamps) is kept --
 *       {@code knowledge_revision_sources} still references it by id, and the wiki's "Log" view
 *       surfaces source refs by id, so only the (potentially large) payload content is reclaimed.</li>
 *   <li><b>Delete DEAD</b> -- DEAD sources older than {@link #deleteDeadAfterDays} (default 90):
 *       hard-deleted entirely. A DEAD source exhausted every retry ({@code KnowledgeIngestScheduler}'s
 *       sweep) without ever being marked PROCESSED, so it normally has no downstream references -- but
 *       a librarian run that outlived the stale window can link a revision to a source <em>after</em>
 *       the sweep dead-lettered it ({@code markProcessed} only moves PENDING/PROCESSING rows, so the
 *       status stays DEAD). {@code knowledge_revision_sources}' {@code ON DELETE CASCADE} would silently
 *       erase that provenance, so each row is checked ({@code isReferencedByRevision}) and a referenced
 *       one is compacted into a tombstone instead of deleted.</li>
 *   <li><b>Delete SKIPPED</b> -- SKIPPED sources older than {@link #deleteSkippedAfterDays} (default
 *       90): same hard-delete-or-tombstone treatment as DEAD. A skip produced no page, so unlike
 *       PROCESSED it is never referenced by {@code knowledge_revision_sources} in the ordinary case --
 *       but the same defensive reference check applies (kept purely as a hedge; not expected to trigger
 *       in practice, since a skip decision writes no page).</li>
 * </ul>
 *
 * <p>All three passes delete the offloaded GCS object <b>before</b> touching the row. If that delete
 * throws, the row is skipped entirely for this tick (its {@code REQUIRES_NEW} transaction rolls back --
 * no field nulled, no row deleted) and retried on the next hourly tick: never null {@code payload_uri}
 * (or delete the row) unless the object it points at is confirmed gone, or the object becomes an
 * unreferenced, uncleanable orphan in the bucket forever.
 */
@Component
public class KnowledgeRetentionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetentionService.class);

    /** Rows processed per sweep per tick -- bounds each tick's work regardless of backlog size. */
    private static final int BATCH_SIZE = 100;

    private final KnowledgeSourceRepository repository;
    private final StorageService storageService;
    private final int compactAfterDays;
    private final int deleteDeadAfterDays;
    private final int deleteSkippedAfterDays;

    /** Self-reference so the {@code REQUIRES_NEW} per-row helpers run through the Spring proxy --
     *  mirrors {@code KnowledgeIngestScheduler#self}; calling them via plain {@code this} would bypass
     *  AOP entirely and silently run with no transaction at all. */
    @Autowired
    @Lazy
    KnowledgeRetentionService self;

    public KnowledgeRetentionService(
            KnowledgeSourceRepository repository,
            StorageService storageService,
            @Value("${conductor.knowledge.retention.processed-days:30}") int compactAfterDays,
            @Value("${conductor.knowledge.retention.dead-days:90}") int deleteDeadAfterDays,
            @Value("${conductor.knowledge.retention.skipped-days:90}") int deleteSkippedAfterDays) {
        this.repository = repository;
        this.storageService = storageService;
        this.compactAfterDays = compactAfterDays;
        this.deleteDeadAfterDays = deleteDeadAfterDays;
        this.deleteSkippedAfterDays = deleteSkippedAfterDays;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void sweep() {
        try {
            compactProcessed();
        } catch (Exception e) {
            log.error("Knowledge retention compaction sweep failed: {}", e.getMessage(), e);
        }
        try {
            deleteTerminalUnfiled(KnowledgeSourceStatus.DEAD, deleteDeadAfterDays);
        } catch (Exception e) {
            log.error("Knowledge retention deletion sweep failed (DEAD): {}", e.getMessage(), e);
        }
        try {
            deleteTerminalUnfiled(KnowledgeSourceStatus.SKIPPED, deleteSkippedAfterDays);
        } catch (Exception e) {
            log.error("Knowledge retention deletion sweep failed (SKIPPED): {}", e.getMessage(), e);
        }
    }

    // ---- compact: PROCESSED, older than compactAfterDays, not yet purged ----

    private void compactProcessed() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(compactAfterDays);
        List<KnowledgeSource> candidates = repository
                .findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                        KnowledgeSourceStatus.PROCESSED, cutoff, PageRequest.of(0, BATCH_SIZE));
        int compacted = 0;
        for (KnowledgeSource source : candidates) {
            try {
                self.compactInNewTx(source.getId());
                compacted++;
            } catch (Exception e) {
                log.warn("Failed to compact knowledge source {} (will retry next tick): {}",
                        source.getId(), e.getMessage());
            }
        }
        if (compacted > 0) {
            log.info("Compacted {} knowledge source(s) past {} days", compacted, compactAfterDays);
        }
    }

    /**
     * Deletes the offloaded GCS object (if any) BEFORE touching the row, and lets a storage-delete
     * failure propagate: the {@code REQUIRES_NEW} transaction then rolls back untouched (no field
     * nulled, no {@code purgedAt} stamped) and the caller's per-item catch logs it for a retry next
     * tick, rather than nulling {@code payload_uri} and orphaning an object still sitting in the bucket.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compactInNewTx(String sourceId) {
        repository.findById(sourceId).ifPresent(source -> {
            if (source.getPurgedAt() != null) {
                return; // already compacted (concurrent tick/instance)
            }
            deleteOffloadedPayloadOrThrow(source);
            source.setPayload(null);
            source.setPayloadUri(null);
            source.setPurgedAt(OffsetDateTime.now());
            repository.save(source);
        });
    }

    /** Propagates a storage-delete failure to the caller -- see {@link #compactInNewTx}/{@link #deleteInNewTx}. */
    private void deleteOffloadedPayloadOrThrow(KnowledgeSource source) {
        String payloadUri = source.getPayloadUri();
        if (payloadUri == null) {
            return;
        }
        storageService.delete(payloadUri);
    }

    // ---- delete: a terminal status that never produced a filed page (DEAD or SKIPPED), older than
    // its own window ----

    /**
     * Shared sweep for the two terminal-but-unfiled statuses: DEAD (exhausted retries, never got a
     * verdict) and SKIPPED (got a verdict, and the verdict was "not worth a page"). Neither leaves a
     * page behind, so both get the same hard-delete-after-a-window treatment -- unlike PROCESSED,
     * which is compacted (payload cleared) but its row kept forever as the wiki's provenance record.
     */
    private void deleteTerminalUnfiled(KnowledgeSourceStatus status, int afterDays) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(afterDays);
        // PurgedAtIsNull: a referenced row gets compacted into a tombstone instead of deleted (see
        // deleteInNewTx) -- the purgedAt stamp takes it out of this query so it can't clog the
        // oldest-first batch on every subsequent tick.
        List<KnowledgeSource> candidates = repository
                .findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                        status, cutoff, PageRequest.of(0, BATCH_SIZE));
        int deleted = 0;
        for (KnowledgeSource source : candidates) {
            try {
                self.deleteInNewTx(source.getId(), status);
                deleted++;
            } catch (Exception e) {
                log.warn("Failed to delete {} knowledge source {} (will retry next tick): {}",
                        status, source.getId(), e.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} {} knowledge source(s) past {} days", deleted, status, afterDays);
        }
    }

    /** Same GCS-first, skip-on-failure rule as {@link #compactInNewTx} -- never delete a row whose
     *  offloaded object might still be sitting in the bucket unreferenced. {@code expected} guards
     *  against a row that raced its way out of the status this sweep claimed it under. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteInNewTx(String sourceId, KnowledgeSourceStatus expected) {
        repository.findById(sourceId).ifPresent(source -> {
            if (source.getStatus() != expected || source.getPurgedAt() != null) {
                return; // raced with something moving it out of the expected status, or already tombstoned
            }
            if (repository.isReferencedByRevision(source.getId())) {
                // A wedged librarian run linked a revision to this source after the stale sweep
                // dead-lettered/skipped it. Hard-deleting would cascade away that provenance row --
                // the exact property compaction exists to protect -- so compact it instead; the
                // tombstone row (purgedAt set) stops matching the compact query and simply stays.
                deleteOffloadedPayloadOrThrow(source);
                source.setPayload(null);
                source.setPayloadUri(null);
                source.setPurgedAt(OffsetDateTime.now());
                repository.save(source);
                return;
            }
            deleteOffloadedPayloadOrThrow(source);
            repository.delete(source);
        });
    }
}
