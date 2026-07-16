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
 * wiki pages are the durable record, not the inbox. Two independent sweeps, each bounded to one batch
 * per tick so a large backlog never holds a long transaction or spikes load:
 *
 * <ul>
 *   <li><b>Compact</b> -- PROCESSED sources older than {@link #compactAfterDays} (default 30): null out
 *       the inline {@code payload} column and delete any offloaded GCS object, then stamp
 *       {@code purgedAt}. The row itself (id, type, ref, metadata, status, timestamps) is kept --
 *       {@code knowledge_revision_sources} still references it by id, and the wiki's "Log" view
 *       surfaces source refs by id, so only the (potentially large) payload content is reclaimed.</li>
 *   <li><b>Delete</b> -- DEAD sources older than {@link #deleteDeadAfterDays} (default 90): hard-deleted
 *       entirely. A DEAD source exhausted every retry ({@code KnowledgeIngestScheduler}'s sweep) without
 *       ever being marked PROCESSED, so by construction nothing downstream (a page revision's linked
 *       sources) references it -- {@code markProcessed} is what flips a source's status in the very same
 *       transaction as the page write that would reference it.</li>
 * </ul>
 *
 * <p>Both passes delete the offloaded GCS object <b>before</b> touching the row. If that delete throws,
 * the row is skipped entirely for this tick (its {@code REQUIRES_NEW} transaction rolls back -- no
 * field nulled, no row deleted) and retried on the next hourly tick: never null {@code payload_uri}
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
            @Value("${conductor.knowledge.retention.dead-days:90}") int deleteDeadAfterDays) {
        this.repository = repository;
        this.storageService = storageService;
        this.compactAfterDays = compactAfterDays;
        this.deleteDeadAfterDays = deleteDeadAfterDays;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void sweep() {
        try {
            compactProcessed();
        } catch (Exception e) {
            log.error("Knowledge retention compaction sweep failed: {}", e.getMessage(), e);
        }
        try {
            deleteDead();
        } catch (Exception e) {
            log.error("Knowledge retention deletion sweep failed: {}", e.getMessage(), e);
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

    // ---- delete: DEAD, older than deleteDeadAfterDays ----

    private void deleteDead() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(deleteDeadAfterDays);
        List<KnowledgeSource> candidates = repository.findByStatusAndReceivedAtBeforeOrderByReceivedAtAsc(
                KnowledgeSourceStatus.DEAD, cutoff, PageRequest.of(0, BATCH_SIZE));
        int deleted = 0;
        for (KnowledgeSource source : candidates) {
            try {
                self.deleteInNewTx(source.getId());
                deleted++;
            } catch (Exception e) {
                log.warn("Failed to delete dead knowledge source {} (will retry next tick): {}",
                        source.getId(), e.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} dead knowledge source(s) past {} days", deleted, deleteDeadAfterDays);
        }
    }

    /** Same GCS-first, skip-on-failure rule as {@link #compactInNewTx} -- never delete a row whose
     *  offloaded object might still be sitting in the bucket unreferenced. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteInNewTx(String sourceId) {
        repository.findById(sourceId).ifPresent(source -> {
            if (source.getStatus() != KnowledgeSourceStatus.DEAD) {
                return; // raced with something moving it out of DEAD
            }
            deleteOffloadedPayloadOrThrow(source);
            repository.delete(source);
        });
    }
}
