package com.conductor.memory;

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
 * Nightly housekeeping for {@code agent_memories}: runs {@link MemoryConsolidationService}'s slow-lane
 * review pass, then a retention sweep over what's left -- mirrors {@code KnowledgeRetentionService}'s
 * shape (batched, per-item {@code REQUIRES_NEW} via the {@code self} proxy, per-item try/catch so one
 * bad row never blocks the rest of the sweep).
 *
 * <p>Retention has two independent passes, each looping in batches of {@value #BATCH_SIZE} until its
 * candidate query comes up empty (capped at {@value #MAX_ITERATIONS} iterations per tick, so a
 * pathological backlog can't turn one tick into an unbounded scan):
 * <ul>
 *   <li><b>Close</b> -- live rows ({@code valid_to IS NULL}) with importance &lt;= 3 that haven't been
 *       touched (accessed or created) in {@link #staleDays} days get their validity window closed. No
 *       {@code supersededBy} is set -- this is aging out, not a replacement.</li>
 *   <li><b>Purge</b> -- rows closed (any reason: consolidation supersession, manual close, or the close
 *       pass above) more than {@link #purgeClosedDays} days ago are hard-deleted. {@code superseded_by}
 *       is {@code ON DELETE SET NULL}, so purging an old closed row can never break a still-live row's
 *       chain.</li>
 * </ul>
 */
@Component
public class MemoryMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceScheduler.class);

    /** Rows processed per pass per iteration -- bounds each iteration's work regardless of backlog size. */
    private static final int BATCH_SIZE = 100;
    /** Safety cap on iterations per pass per tick, so a pathological backlog can't run forever. */
    private static final int MAX_ITERATIONS = 50;
    /** Importance ceiling for the "stale low-importance" close pass. */
    private static final int STALE_MAX_IMPORTANCE = 3;

    private final MemoryConsolidationService consolidationService;
    private final AgentMemoryRepository repository;
    private final boolean enabled;
    private final int staleDays;
    private final int purgeClosedDays;

    /** Self-reference so the {@code REQUIRES_NEW} per-row helpers run through the Spring proxy -- see
     *  {@code KnowledgeRetentionService#self} for why plain {@code this} calls would silently run with
     *  no transaction at all. */
    @Autowired
    @Lazy
    MemoryMaintenanceScheduler self;

    public MemoryMaintenanceScheduler(MemoryConsolidationService consolidationService,
                                      AgentMemoryRepository repository,
                                      @Value("${conductor.memory.maintenance.enabled:true}") boolean enabled,
                                      @Value("${conductor.memory.retention.stale-days:90}") int staleDays,
                                      @Value("${conductor.memory.retention.purge-closed-days:90}") int purgeClosedDays) {
        this.consolidationService = consolidationService;
        this.repository = repository;
        this.enabled = enabled;
        this.staleDays = staleDays;
        this.purgeClosedDays = purgeClosedDays;
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            consolidationService.consolidateAll();
        } catch (Exception e) {
            log.error("Memory consolidation pass failed: {}", e.getMessage(), e);
        }
        try {
            closeStaleLowImportance();
        } catch (Exception e) {
            log.error("Memory retention close pass failed: {}", e.getMessage(), e);
        }
        try {
            purgeOldClosed();
        } catch (Exception e) {
            log.error("Memory retention purge pass failed: {}", e.getMessage(), e);
        }
    }

    private void closeStaleLowImportance() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(staleDays);
        int closed = 0;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            List<AgentMemory> batch = repository.findStaleLowImportance(
                    STALE_MAX_IMPORTANCE, cutoff, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            for (AgentMemory memory : batch) {
                try {
                    self.closeInNewTx(memory.getId());
                    closed++;
                } catch (Exception e) {
                    log.warn("Failed to close stale memory {} (will retry next tick): {}",
                            memory.getId(), e.getMessage());
                }
            }
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        if (closed > 0) {
            log.info("Closed {} stale low-importance memory row(s) past {} days", closed, staleDays);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closeInNewTx(String memoryId) {
        repository.findById(memoryId).ifPresent(memory -> {
            if (memory.getValidTo() == null) {
                memory.setValidTo(OffsetDateTime.now());
                repository.save(memory);
            }
        });
    }

    private void purgeOldClosed() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(purgeClosedDays);
        int purged = 0;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            List<AgentMemory> batch = repository.findByValidToLessThan(cutoff, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            for (AgentMemory memory : batch) {
                try {
                    self.purgeInNewTx(memory.getId());
                    purged++;
                } catch (Exception e) {
                    log.warn("Failed to purge closed memory {} (will retry next tick): {}",
                            memory.getId(), e.getMessage());
                }
            }
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        if (purged > 0) {
            log.info("Purged {} closed memory row(s) past {} days", purged, purgeClosedDays);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purgeInNewTx(String memoryId) {
        repository.findById(memoryId).ifPresent(repository::delete);
    }
}
