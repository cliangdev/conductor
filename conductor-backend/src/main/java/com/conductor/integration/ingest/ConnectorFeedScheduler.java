package com.conductor.integration.ingest;

import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.knowledge.MetricsNarratorDispatchService;
import com.conductor.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Drives the connector-feed pipeline end to end: pulls due feeds, dispatches PENDING digests to the
 * {@code metrics-narrator} workflow, and sweeps NARRATING digests whose run finished (successfully or
 * not) or stalled. Structurally mirrors {@code KnowledgeIngestScheduler}: one {@code @Scheduled} tick,
 * no method-level {@code @Transactional} (a claim's row locks must release before the outbound HTTP
 * pull or the workflow-run-creating dispatch call runs), short {@code REQUIRES_NEW} helpers via the
 * {@code self} proxy pattern, and per-item try/catch so one feed's or digest's failure never blocks
 * the rest of the tick.
 *
 * <p>Unlike {@code KnowledgeIngestScheduler}, this scheduler's claimed rows are globally scoped (no
 * per-project/per-lane concurrency unit) and a pull can enqueue a whole workflow run's worth of jobs --
 * see {@code src/test/resources/application.properties}'s {@code conductor.connector-feed.enabled=false}
 * for why the handful of tests that need this scheduler live carry their own {@code @Container} and
 * flip it back on, rather than letting it run against the shared test database like most schedulers do.
 */
@Component
public class ConnectorFeedScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConnectorFeedScheduler.class);

    /** Package-private (not final) so tests can shrink the batch without waiting for a full page. */
    int batchSize = 10;

    /** In-flight marker stamped on a just-claimed feed so a concurrent tick can't reclaim it before
     *  this tick's pull (outside the claim transaction) finishes and applies the real outcome. Well
     *  under a feed's minimum sane {@code interval_minutes}, and refreshed every tick regardless. */
    static final int CLAIM_BUFFER_SECONDS = 120;

    static final int MAX_CONSECUTIVE_FAILURES = 8;
    static final int MAX_DIGEST_ATTEMPTS = 5;

    /** Package-private (not final) so tests can shrink the stale-narrating window instead of waiting
     *  out the real duration -- same pattern as {@code KnowledgeIngestScheduler#staleProcessingMinutes}. */
    long staleNarratingMinutes = 30;

    /** Mirrors {@code KnowledgeIngestScheduler#TERMINAL_FAILED_STATUSES} -- kept as a separate constant
     *  rather than a shared reference since the two schedulers otherwise have no coupling. */
    private static final Set<WorkflowRunStatus> TERMINAL_FAILED_STATUSES =
            Set.of(WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED, WorkflowRunStatus.LOCAL_PICKUP_TIMEOUT);

    private final ConnectorFeedRepository feedRepository;
    private final ConnectorFeedDigestRepository digestRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final FeedPullService feedPullService;
    private final MetricsNarratorDispatchService narratorDispatchService;
    private final DigestSubmissionService digestSubmissionService;
    private final boolean enabled;

    /** Self-reference so {@code @Transactional(REQUIRES_NEW)} helpers run through the Spring proxy. */
    @Autowired
    @Lazy
    ConnectorFeedScheduler self;

    public ConnectorFeedScheduler(ConnectorFeedRepository feedRepository,
                                  ConnectorFeedDigestRepository digestRepository,
                                  WorkflowRunRepository workflowRunRepository,
                                  FeedPullService feedPullService,
                                  MetricsNarratorDispatchService narratorDispatchService,
                                  DigestSubmissionService digestSubmissionService,
                                  @Value("${conductor.connector-feed.enabled:true}") boolean enabled) {
        this.feedRepository = feedRepository;
        this.digestRepository = digestRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.feedPullService = feedPullService;
        this.narratorDispatchService = narratorDispatchService;
        this.digestSubmissionService = digestSubmissionService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60_000)
    public void poll() {
        if (!enabled) {
            return;
        }
        pullDueFeeds();
        dispatchPendingDigests();
        sweepNarratingDigests();
    }

    // ---- pull: claim due feeds, pull each outside any transaction, apply the outcome's backoff ----

    private void pullDueFeeds() {
        OffsetDateTime now = OffsetDateTime.now();
        for (String feedId : self.claimFeedsInNewTx(now, batchSize)) {
            try {
                pullOneFeed(feedId, now);
            } catch (Exception e) {
                log.error("Connector feed pull failed for feed {}: {}", feedId, e.getMessage(), e);
            }
        }
    }

    private void pullOneFeed(String feedId, OffsetDateTime now) {
        boolean hasMore = feedPullService.pull(feedId);
        self.applyPostPullSchedulingInNewTx(feedId, now, hasMore);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> claimFeedsInNewTx(OffsetDateTime now, int limit) {
        List<ConnectorFeed> due = feedRepository.claimDue(now, limit);
        List<String> ids = new ArrayList<>(due.size());
        for (ConnectorFeed feed : due) {
            // Provisional in-flight stamp only -- applyPostPullSchedulingInNewTx overwrites this with
            // the real outcome once the pull (outside this transaction) finishes.
            feed.setNextRunAt(now.plusSeconds(CLAIM_BUFFER_SECONDS));
            ids.add(feed.getId());
        }
        feedRepository.saveAll(due);
        return ids;
    }

    /**
     * Corrects {@code next_run_at}/{@code status} once a pull has actually finished: a feed left
     * {@code SETUP_REQUIRED} by the pull is stamped 6h out WITHOUT counting as a failure (a
     * misconfiguration is not a transient fault and must not burn the dead-letter budget); a feed with
     * {@code consecutiveFailures > 0} (the DEGRADED path) gets exponential backoff capped at its own
     * {@code interval_minutes} -- so a weekly feed backs off to at most a week, never a day past its own
     * cadence -- or DEAD past {@link #MAX_CONSECUTIVE_FAILURES}; otherwise the pull succeeded and {@code
     * FeedPullService} already set the correct {@code next_run_at}, except when {@code hasMore} says
     * there's more to drain, in which case this re-dues it for the very next tick instead.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyPostPullSchedulingInNewTx(String feedId, OffsetDateTime now, boolean hasMore) {
        feedRepository.findById(feedId).ifPresent(feed -> {
            if (feed.getStatus() == ConnectorFeedStatus.SETUP_REQUIRED) {
                feed.setNextRunAt(now.plusHours(6));
                feedRepository.save(feed);
                return;
            }
            if (feed.getConsecutiveFailures() > 0) {
                if (feed.getConsecutiveFailures() >= MAX_CONSECUTIVE_FAILURES) {
                    feed.setStatus(ConnectorFeedStatus.DEAD);
                } else {
                    long backoffMinutes = Math.min(feed.getIntervalMinutes(), 60L * (1L << feed.getConsecutiveFailures()));
                    feed.setNextRunAt(now.plusMinutes(backoffMinutes));
                }
                feedRepository.save(feed);
                return;
            }
            if (hasMore) {
                feed.setNextRunAt(now);
                feedRepository.save(feed);
            }
        });
    }

    // ---- dispatch: PENDING digest -> NARRATING + metrics-narrator run, one dispatch per digest ----

    private void dispatchPendingDigests() {
        for (ConnectorFeedDigest digest : self.claimDigestsInNewTx(batchSize)) {
            try {
                narratorDispatchService.dispatch(digest);
            } catch (Exception e) {
                log.error("Metrics-narrator dispatch failed for digest {}: {}", digest.getId(), e.getMessage(), e);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ConnectorFeedDigest> claimDigestsInNewTx(int limit) {
        List<ConnectorFeedDigest> due = digestRepository.claimDuePending(OffsetDateTime.now(), limit);
        for (ConnectorFeedDigest digest : due) {
            digest.setStatus(DigestStatus.NARRATING);
        }
        digestRepository.saveAll(due);
        return due;
    }

    // ---- sweep: resolve NARRATING digests whose run succeeded, failed, or stalled ----

    private void sweepNarratingDigests() {
        OffsetDateTime staleCutoff = OffsetDateTime.now().minusMinutes(staleNarratingMinutes);
        for (ConnectorFeedDigest digest : digestRepository.findByStatus(DigestStatus.NARRATING)) {
            try {
                sweepOneDigest(digest, staleCutoff);
            } catch (Exception e) {
                log.error("Digest sweep failed for digest {}: {}", digest.getId(), e.getMessage(), e);
            }
        }
    }

    private void sweepOneDigest(ConnectorFeedDigest digest, OffsetDateTime staleCutoff) {
        String runId = digest.getNarratingRunId();
        WorkflowRun run = runId != null ? workflowRunRepository.findById(runId).orElse(null) : null;

        if (run == null || isTerminallyFailedOrStale(run, staleCutoff)) {
            self.resurrectOrDeadDigestInNewTx(digest.getId());
            return;
        }
        if (run.getStatus() == WorkflowRunStatus.SUCCESS) {
            boolean submitted = digestSubmissionService.trySubmit(digest, run.getId());
            if (!submitted) {
                // A SUCCEEDED run with a blank/missing narrative is a failed attempt, not a success.
                self.resurrectOrDeadDigestInNewTx(digest.getId());
            }
            return;
        }
        // Still running/pending and not stale -- leave it alone; a later tick re-checks.
    }

    private boolean isTerminallyFailedOrStale(WorkflowRun run, OffsetDateTime staleCutoff) {
        return TERMINAL_FAILED_STATUSES.contains(run.getStatus())
                || (run.getStartedAt() != null && run.getStartedAt().isBefore(staleCutoff));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resurrectOrDeadDigestInNewTx(String digestId) {
        digestRepository.findById(digestId).ifPresent(digest -> {
            if (digest.getStatus() != DigestStatus.NARRATING) {
                return; // already moved on (raced with a submission in the meantime)
            }
            int attempts = digest.getAttempts() + 1;
            digest.setAttempts(attempts);
            if (attempts >= MAX_DIGEST_ATTEMPTS) {
                digest.setStatus(DigestStatus.DEAD);
                digest.setErrorMessage("Exceeded " + MAX_DIGEST_ATTEMPTS + " narration attempts (last run: "
                        + digest.getNarratingRunId() + ")");
            } else {
                digest.setStatus(DigestStatus.PENDING);
                long backoffSeconds = 60L * (1L << attempts);
                digest.setNextAttemptAt(OffsetDateTime.now().plusSeconds(backoffSeconds));
            }
            digestRepository.save(digest);
        });
    }
}
