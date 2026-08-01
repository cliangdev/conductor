package com.conductor.disposition;

import com.conductor.signal.SignalGlob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-project snapshot of {@link DispositionPolicy} rows, so {@code
 * DispositionPolicySubscriber} never does a DB round-trip per {@code publish()} -- {@code
 * InProcessSignalBus.publish} runs inside every caller's own transaction, including hot paths like
 * {@code WorkItemService#patchWorkItem}, so an extra query per signal per subscriber is not free.
 *
 * <p>Cache-aside with explicit invalidation rather than a TTL: a project's enabled policies are loaded
 * once on first read and kept until {@link #invalidate} is called (by whoever writes {@code
 * disposition_policy} for that project -- today only {@code ConnectorFeedProvisioner}'s seeding path;
 * a future policy-management API must call this too). {@link #matching} fails OPEN on any read error --
 * returns an empty match list rather than propagating -- because a broken cache/DB read must never make
 * {@code DispositionPolicySubscriber} veto something it otherwise wouldn't have; the alternative
 * (failing closed) would turn a transient DB hiccup into a silent, hard-to-diagnose block on whatever
 * this subscriber gates.
 */
@Component
public class DispositionPolicyCache {

    private static final Logger log = LoggerFactory.getLogger(DispositionPolicyCache.class);

    private final DispositionPolicyRepository repository;
    private final ConcurrentHashMap<String, List<DispositionPolicy>> byProject = new ConcurrentHashMap<>();

    public DispositionPolicyCache(DispositionPolicyRepository repository) {
        this.repository = repository;
    }

    /** Enabled policies for {@code projectId} whose {@code signalType} glob matches {@code signalType}
     *  (see {@link SignalGlob}). Empty for a project with no rows -- the empty-table no-op case. */
    public List<DispositionPolicy> matching(String projectId, String signalType) {
        List<DispositionPolicy> policies;
        try {
            policies = byProject.computeIfAbsent(projectId, id -> List.copyOf(repository.findByProjectIdAndEnabledTrue(id)));
        } catch (Exception e) {
            log.warn("Failed to load disposition policies for project {} -- failing open (no match): {}",
                    projectId, e.getMessage());
            return List.of();
        }
        return policies.stream().filter(p -> SignalGlob.matches(p.getSignalType(), signalType)).toList();
    }

    /**
     * Drops {@code projectId}'s cached snapshot so the next {@link #matching} call re-reads it -- call
     * after any write to {@code disposition_policy} for that project.
     *
     * <p>When called inside an active transaction the drop is DEFERRED until after commit. Dropping
     * immediately would open a window in which another thread's {@link #matching} repopulates the
     * snapshot via {@code computeIfAbsent} from a still-uncommitted read -- i.e. WITHOUT the row being
     * written -- and, because nothing invalidates again after the commit lands, that stale snapshot
     * would survive until some unrelated write happened to touch the same project. Deferring makes the
     * drop happen when the new row is actually visible. Also correct on rollback: the write never
     * landed, so the cached snapshot was right all along and is left alone.
     */
    public void invalidate(String projectId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    byProject.remove(projectId);
                }
            });
            return;
        }
        byProject.remove(projectId);
    }
}
