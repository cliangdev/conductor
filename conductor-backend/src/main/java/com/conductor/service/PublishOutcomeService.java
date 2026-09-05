package com.conductor.service;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.integration.ActionResult;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.publish.PublishPlatform;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.service.publish.PublishingWorkflow;
import com.conductor.repository.WorkItemRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What became of one {@code (post, target)} publish (COND-23 T6.1): the single place a platform's answer
 * is turned into durable state. Both lanes end here — {@code PostPublishScheduler} for APP_MANAGED and
 * {@code NativeHandoffService} for the platform's own confirmation — so "published" means the same thing,
 * and produces the same evidence, however the post got out.
 *
 * <h2>A published destination is an Asset</h2>
 * A success is not just a state change: it records one typed Asset on the Post whose {@code ref} is the
 * permalink and whose {@code type} names the destination platform ({@code facebook_post},
 * {@code instagram_post}, {@code youtube_video}, {@code tiktok_post} — exactly the four the MARKETING
 * workflow declares in its {@code asset_types}). That is COND-19's model: a Post going to three accounts
 * produces three Assets, each one a link a human can click, rather than one blob of publish metadata.
 * Recording goes through {@link AssetService#recordAsset} — the system path, which takes no caller and is
 * idempotent on {@code (workItem, type, ref)}, so re-applying a result never doubles an Asset.
 *
 * <h2>A failure is a message a human has to read</h2>
 * The platform's error text is stored <b>verbatim</b>: "(#100) The parameter image_url is required" is
 * what lets someone fix the post, and no amount of rewording improves it. When the failure is a permanent
 * auth/permission problem rather than a transient one, the connection is additionally marked unhealthy via
 * {@link ConnectionHealthService#reportPublishAuthFailure} so it surfaces on the Integrations page instead
 * of quietly failing again at the next fire time. Transient failures — rate limits, 5xx, timeouts — never
 * cost a connection its health; see {@link #isPermanentAuthFailure}.
 *
 * <h2>Terminal states are never overwritten</h2>
 * {@code REVOKED} and {@code PUBLISHED} are respected: a late failure never buries a success, and a
 * revocation is never undone by an outcome that was already in flight when it landed. Every entry point
 * returns whether <em>this</em> call is what moved the row, and a call that <em>did</em> move one is what
 * triggers the Post-level roll-up below.
 *
 * <h2>The Post-level roll-up (T6.2)</h2>
 * A target is one destination; a human reads the Post. So every time a target lands in a terminal state the
 * Post is re-evaluated: once nothing is still in flight, <b>every</b> target {@code PUBLISHED} moves the Post
 * to its published status, and <b>any</b> failure moves it to its failed status. A mixed outcome therefore
 * reads <b>Failed</b> — "needs attention", not "nothing published": the targets that did go out keep their
 * {@code PUBLISHED} state and their permalink Assets, and those are what the Post detail shows alongside the
 * failure. Reading a mixed Post as Published would be the dangerous direction; reading it as Failed is merely
 * pessimistic, and the per-target evidence is right there to disambiguate.
 *
 * <p>{@code REVOKED} targets are excluded from the tally entirely — a destination that was taken back is not
 * an outcome — and the roll-up only ever fires while the Post is still in its scheduled status, so it can
 * never drag a Post that a human or {@link PublishBundleGuard} has since moved back into the pipeline.
 *
 * <p>Both statuses come off the Work Item's own version-pinned {@link Statechart}, never a hardcoded name:
 * see {@link #publishedStatus} and {@link #failedStatus}. The roll-up writes the status and nothing else —
 * it deliberately does not publish {@code WORK_ITEM_STATUS_CHANGED}, because that enrichment lives on
 * {@code WorkItemService}, which depends (through {@code PublishBundleGuard} → {@code NativeHandoffService})
 * on this service. Announcing the roll-up is the call site's to wire.
 *
 * <h2>Retry, and why the key has to be new</h2>
 * {@link #retryFailedTargets} re-fires <em>only</em> {@code FAILED} rows. Each is reset to {@code PENDING}
 * with a <b>freshly minted</b> {@code idempotency_key}: {@link ActionInvocationService} is claim-or-return on
 * that key, so reusing it would hand back the failed attempt's stored result forever instead of posting.
 * A {@code PUBLISHED} row is not touched by any part of it.
 *
 * <h2>Stranded rows are reconciled, never re-dispatched</h2>
 * {@code PostPublishScheduler} claims a row {@code PENDING -> PUBLISHING} before it calls the platform. A
 * process that dies in between leaves that row {@code PUBLISHING} forever — no poller picks it up, and no
 * human ever hears about it. {@link #reconcileStrandedTargets} resolves such a row into {@code FAILED} once
 * it is past {@link #strandedAfter}, and the Post rolls up as usual.
 *
 * <p>What it must <em>not</em> do is republish it. The row was handed to the platform; the post may well be
 * live. So the recorded message says the outcome is <em>unknown</em> and asks a human to look, and the row
 * lands in {@code FAILED} — a state neither poller dispatches — rather than back in {@code PENDING}. Getting
 * it out again is then an explicit human retry, which is exactly the at-most-once boundary this pipeline is
 * built on.
 *
 * <h2>Transactions</h2>
 * Every entry point runs {@code REQUIRES_NEW}. The platform side effect has already happened by the time
 * this service is called; the record of it must not roll back with whatever the caller does next.
 */
@Service
public class PublishOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(PublishOutcomeService.class);

    /** The output key every publish action reports its public URL under. */
    private static final String PERMALINK_OUTPUT_KEY = "permalink";

    private static final String NO_RESULT_MESSAGE = "The publish action returned no result";

    /**
     * Failures that mean "this connection's credentials will not work again until a human reconnects",
     * as opposed to "try later". Matched against the platform's own error text because that is all an
     * {@link ActionResult} carries — a caller that knows better passes the verdict explicitly to
     * {@link #recordFailure(String, String, boolean)}.
     *
     * <p>Deliberately conservative: a rate limit, a 5xx and a timeout must all fall through, because
     * wrongly marking a connection unhealthy sends someone to re-authorize an account that was fine.
     */
    private static final Pattern PERMANENT_AUTH_FAILURE = Pattern.compile(
            "\\b(401|403)\\b"
                    + "|unauthori[sz]ed|forbidden"
                    + "|invalid[ _-]?(grant|token|credential|oauth)"
                    + "|oauth ?exception"
                    + "|(access[ _-]?token|credential|session|grant|permission|authorization)s?"
                    + "[^.;\\n]{0,40}?(expired|revoked|invalid|denied)"
                    + "|expired[ _-]?(access[ _-]?)?token"
                    + "|re-?authenticat|re-?authori[sz]|log ?in again"
                    + "|insufficient[ _-]?(authentication[ _-]?)?(permission|scope|privilege|credential)"
                    + "|not[ _-]?granted[^.;\\n]{0,40}?permission"
                    + "|missing[ _-]?(required[ _-]?)?(scope|permission)"
                    + "|authentication[ _-]?fail",
            Pattern.CASE_INSENSITIVE);

    /**
     * States that mean the Post has not finished publishing yet; while any target sits in one, the roll-up
     * holds off.
     *
     * <p>{@link PostPublishTargetState#AWAITING_MANUAL} belongs here even though nothing automated will ever
     * move it. The roll-up's rule is "every non-revoked target published, or the Post failed", so treating a
     * manual target as settled the moment it came due would roll the Post straight to its failed status
     * — reporting as a publishing failure a post whose human simply has not got to it yet. It is the one
     * in-flight state with no timeout, which is why the stranded sweep (which only ever queries
     * {@code PUBLISHING}) must never be widened to cover it.
     */
    private static final Set<PostPublishTargetState> IN_FLIGHT = Set.of(
            PostPublishTargetState.PENDING,
            PostPublishTargetState.HANDED_OFF,
            PostPublishTargetState.PUBLISHING,
            PostPublishTargetState.AWAITING_MANUAL);

    /**
     * What a stranded row records. Every word is load-bearing: the dispatch reached the platform, so the
     * post may be live, and a human has to look before re-firing it.
     */
    static final String STRANDED_MESSAGE =
            "Publishing was interrupted after this post was handed to the platform, and no outcome ever came"
                    + " back. The outcome is unknown — the post may or may not have gone out. Check the"
                    + " account before retrying, because a retry will post again if it did not.";

    /**
     * The stranded finder. Not on {@link PostPublishTargetRepository} because this is the only caller;
     * issued through the shared {@code EntityManager} the same way the two schedulers issue their claims.
     */
    private static final String STRANDED_QUERY = """
            SELECT t.id FROM PostPublishTarget t
             WHERE t.state = com.conductor.entity.PostPublishTargetState.PUBLISHING
               AND t.updatedAt <= :cutoff
             ORDER BY t.updatedAt ASC
            """;

    /**
     * The reconciliation, taken atomically. The {@code state = PUBLISHING} and {@code updatedAt <= :cutoff}
     * predicates are re-evaluated under the row lock, so a real outcome landing at the same moment wins the
     * race and this updates nothing. {@code updatedAt} is set explicitly because {@code @PreUpdate} does not
     * fire for a bulk update.
     */
    private static final String RECONCILE_QUERY = """
            UPDATE PostPublishTarget t
               SET t.state = com.conductor.entity.PostPublishTargetState.FAILED,
                   t.errorMessage = :message,
                   t.attempts = t.attempts + 1,
                   t.updatedAt = :now
             WHERE t.id = :id
               AND t.state = com.conductor.entity.PostPublishTargetState.PUBLISHING
               AND t.updatedAt <= :cutoff
            """;

    /**
     * How long a row may sit in {@code PUBLISHING} before it is presumed stranded. Deliberately generous:
     * an app-managed publish uploads the media inline, and a long video on a slow connection is a normal
     * publish, not a dead process. Overriding it via {@code conductor.post-publish.stranded-after-minutes}
     * costs no config file change because the default is declared here.
     */
    private final Duration strandedAfter;

    /** Bounds one reconciliation pass; anything not reached is picked up on the next one. */
    int reconcileBatchSize = 50;

    /**
     * How far out a retried NATIVE target is re-timed. A native platform refuses a schedule inside its
     * minimum lead time, and {@code NativeHandoffService} re-evaluates that window at the sweep's own
     * "now" — some minutes after this call — so a retry stamped at exactly the minimum would be refused
     * for being too soon and the row would sit {@code PENDING} forever. Doubling the lead is the headroom.
     */
    static final Duration NATIVE_RETRY_LEAD = PostScheduleValidator.MINIMUM_LEAD_TIME.multipliedBy(2);

    private final PublishPlatformRegistry platformRegistry;
    private final PostPublishTargetRepository targetRepository;
    private final AssetService assetService;
    private final ConnectionHealthService connectionHealthService;
    private final WorkItemRepository workItemRepository;
    private final WorkflowDefinitionResolver resolver;
    private final ProjectSecurityService projectSecurityService;
    private final boolean reconciliationEnabled;

    /**
     * Transaction-bound shared {@code EntityManager}, used for the stranded finder and the conditional
     * reconciliation UPDATE. Field-injected because that is the supported form of
     * {@code @PersistenceContext}; package-private so unit tests can supply a stub.
     */
    @PersistenceContext
    EntityManager entityManager;

    /** Self-reference so the {@code REQUIRES_NEW} reconciliation runs through the Spring proxy. */
    @Autowired
    @Lazy
    PublishOutcomeService self;

    /**
     * Injected {@code @Lazy} because the dependency is a cycle: {@code WorkItemService} reaches this
     * service through the publishing services it owns. Lazy resolution is the pattern already used for the
     * scheduler self-reference below, and is why the roll-up can announce a status change at all.
     */
    @Autowired
    @Lazy
    private WorkItemService workItemService;

    public PublishOutcomeService(PublishPlatformRegistry platformRegistry,
                                 PostPublishTargetRepository targetRepository,
                                 AssetService assetService,
                                 ConnectionHealthService connectionHealthService,
                                 WorkItemRepository workItemRepository,
                                 WorkflowDefinitionResolver resolver,
                                 ProjectSecurityService projectSecurityService,
                                 @Value("${conductor.post-publish.enabled:true}") boolean reconciliationEnabled,
                                 @Value("${conductor.post-publish.stranded-after-minutes:60}")
                                 long strandedAfterMinutes) {
        this.platformRegistry = platformRegistry;
        this.targetRepository = targetRepository;
        this.assetService = assetService;
        this.connectionHealthService = connectionHealthService;
        this.workItemRepository = workItemRepository;
        this.resolver = resolver;
        this.projectSecurityService = projectSecurityService;
        this.reconciliationEnabled = reconciliationEnabled;
        this.strandedAfter = Duration.ofMinutes(strandedAfterMinutes);
    }

    /**
     * Applies a publish invocation's result to the target it was invoked for: the success path when the
     * platform accepted the post, the failure path otherwise (a {@code null} result included — a publish
     * that reported nothing back is a failure, never a silent no-op).
     *
     * @return true when this call is what moved the row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordOutcome(String targetId, ActionResult result) {
        PostPublishTarget target = find(targetId);
        if (target == null) {
            return false;
        }
        if (result == null || !result.success()) {
            String message = result == null ? NO_RESULT_MESSAGE : result.message();
            return applyFailure(target, message, isPermanentAuthFailure(message));
        }
        Map<String, Object> output = result.output() == null ? Map.of() : result.output();
        PublishPlatform platform = platformFor(target.getPlatform());
        String platformPostId = platform == null ? null : stringValue(output, platform.publish().postIdOutputKey());
        return applySuccess(target, platformPostId, stringValue(output, PERMALINK_OUTPUT_KEY));
    }

    /**
     * The platform published this target: stores what it created, moves the row to {@code PUBLISHED} and
     * records the destination as a typed Asset on the Post.
     *
     * <p>A blank {@code permalink} still publishes the row — the post really did go out — but records no
     * Asset, because an Asset with no link is not evidence of anything.
     *
     * @return true when this call is what moved the row; false when it was already published, was revoked,
     *         or no longer exists
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordSuccess(String targetId, String platformPostId, String permalink) {
        PostPublishTarget target = find(targetId);
        return target != null && applySuccess(target, platformPostId, permalink);
    }

    /**
     * The platform refused this target, classifying the failure from its own error text. Equivalent to
     * {@link #recordFailure(String, String, boolean)} with {@link #isPermanentAuthFailure}'s verdict.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(String targetId, String errorMessage) {
        PostPublishTarget target = find(targetId);
        return target != null && applyFailure(target, errorMessage, isPermanentAuthFailure(errorMessage));
    }

    /**
     * The platform refused this target: moves the row to {@code FAILED}, stores {@code errorMessage}
     * verbatim, and bumps the attempt count.
     *
     * @param permanentAuthFailure true only when the platform rejected our identity or permissions for
     *                             good — the connection is then marked unhealthy. A rate limit, a 5xx or
     *                             a timeout is not one, and must not cost the connection its health.
     * @return true when this call is what moved the row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(String targetId, String errorMessage, boolean permanentAuthFailure) {
        PostPublishTarget target = find(targetId);
        return target != null && applyFailure(target, errorMessage, permanentAuthFailure);
    }

    /**
     * Records that a human published a {@link PublishLane#MANUAL} target by hand.
     *
     * <p>This is the only way a target reaches {@code PUBLISHED} without a platform having told us so, which
     * is exactly why it is narrow. It refuses any target that is not on the MANUAL lane: an automated target
     * has a poller that will publish it and report the real outcome, and letting a human declare it published
     * would strand a post that is still queued to go out — or, worse, mark as published one that later fails,
     * with nothing left watching. A caller wanting to abandon an automated target should deselect it.
     *
     * <p>Everything downstream is the ordinary success path: {@link #applySuccess} moves the row, stores the
     * permalink, records the typed destination Asset and rolls the Post up. A manual publish therefore lands
     * on the calendar, in the outcome panel and in the Asset library identically to an API one — the lane is
     * a detail of who did the posting, not a second class of result.
     *
     * <p>Idempotent in the way that matters: a second call on an already-published target changes nothing and
     * returns false, so a double-clicked button or a retried MCP call cannot produce two destination Assets.
     *
     * @param publishedAt when the human says it actually went out; null means now. Recorded as the row's fire
     *                    time so the calendar shows when the post really happened rather than when it was
     *                    scheduled to
     * @return true when this call is what moved the row
     * @throws BusinessException when the target is not manual, or is in a state a human cannot complete
     */
    @Transactional
    public boolean completeManualTarget(String projectId, String workItemId, String targetId,
                                        String permalink, OffsetDateTime publishedAt, User caller) {
        if (caller == null || !projectSecurityService.isProjectMember(projectId, caller.getId())) {
            // A non-member must not be able to tell a project apart from one that does not exist.
            throw new EntityNotFoundException("Project not found");
        }
        WorkItem post = workItemRepository.findById(workItemId)
                .filter(item -> item.getProject() != null && projectId.equals(item.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));

        PostPublishTarget target = targetRepository.findById(targetId)
                .filter(t -> t.getWorkItem() != null && post.getId().equals(t.getWorkItem().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Publish target not found"));

        if (target.getLane() != PublishLane.MANUAL) {
            throw new BusinessException("This destination publishes automatically, so it cannot be marked"
                    + " published by hand — its outcome is recorded when the platform reports it.");
        }
        if (target.getState() == PostPublishTargetState.REVOKED) {
            throw new BusinessException("This destination was taken back down, so it cannot be marked"
                    + " published. Re-select it and schedule the post again.");
        }
        if (permalink == null || permalink.isBlank()) {
            throw new BusinessException("A link to the published post is required — it is the only record"
                    + " that this destination actually went out.");
        }

        // The fire time becomes when it really went out, so the calendar and the outcome panel agree with
        // reality rather than with the plan. Only on the call that actually publishes: re-stamping on a
        // duplicate would let a second click quietly rewrite the recorded time.
        if (target.getState() != PostPublishTargetState.PUBLISHED) {
            target.setFireTime(publishedAt == null ? OffsetDateTime.now() : publishedAt);
        }
        return applySuccess(target, null, permalink.trim());
    }

    /**
     * One publish target, membership-checked and scoped to its Post. Exists so a caller that has just
     * changed a target can read back what it now looks like without widening any repository's exposure —
     * the mutating call and its read-back share the same scoping rules, so neither can reach a row the
     * other could not.
     */
    @Transactional(readOnly = true)
    public PostPublishTarget readTarget(String projectId, String workItemId, String targetId, User caller) {
        if (caller == null || !projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
        WorkItem post = workItemRepository.findById(workItemId)
                .filter(item -> item.getProject() != null && projectId.equals(item.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        return targetRepository.findById(targetId)
                .filter(t -> t.getWorkItem() != null && post.getId().equals(t.getWorkItem().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Publish target not found"));
    }

    /**
     * What a retry did: the Post as it stands afterwards, every one of its targets, and how many were
     * actually re-fired.
     */
    public record RetryResult(WorkItem post, List<PostPublishTarget> targets, int retried) {}

    /**
     * Re-fires the Post's {@code FAILED} publish targets and nothing else.
     *
     * <p>Each failed row is reset to {@code PENDING}, its stored error cleared, and — the load-bearing part —
     * stamped with a <b>fresh</b> {@code idempotency_key}. {@link ActionInvocationService} is claim-or-return
     * on that key, so re-dispatching under the old one would replay the failed attempt's recorded result
     * instead of posting. A {@code PUBLISHED}, {@code REVOKED} or still-in-flight row is never touched, so a
     * destination that already went out is never published twice and keeps its permalink Asset.
     *
     * <p>The Post moves back to its scheduled status so the due poller picks the reset rows up, and the retry
     * is refused unless the Post is already sitting at its failed or its scheduled status — a Post that a
     * bundle edit has sent back for review must not re-publish under an approval that no longer describes it.
     *
     * <p>A Post with no failed targets is a no-op that reports the current state, which makes a double-click
     * harmless rather than an error.
     *
     * <p>Runs in the caller's transaction (not {@code REQUIRES_NEW}): unlike the outcome-recording entry
     * points, nothing has reached a platform yet, so a failure here should roll the whole request back.
     *
     * @throws EntityNotFoundException when the caller is not a member, or the Post is not in the project
     * @throws BusinessException when the Post is not in a status a retry can fire from
     */
    @Transactional
    public RetryResult retryFailedTargets(String projectId, String workItemId, User caller) {
        if (caller == null || !projectSecurityService.isProjectMember(projectId, caller.getId())) {
            // A non-member must not be able to tell a project apart from one that does not exist.
            throw new EntityNotFoundException("Project not found");
        }
        WorkItem post = workItemRepository.findById(workItemId)
                .filter(item -> item.getProject() != null && projectId.equals(item.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));

        List<PostPublishTarget> targets = targetRepository.findAllByWorkItemId(post.getId());
        List<PostPublishTarget> failed = targets.stream()
                .filter(target -> target.getState() == PostPublishTargetState.FAILED)
                .toList();
        if (failed.isEmpty()) {
            log.info("Retry requested for post {} but no target has failed; nothing to re-fire", post.getId());
            return new RetryResult(post, sorted(targets), 0);
        }

        Statechart statechart = statechartFor(post);
        String scheduledStatus = scheduledStatus(statechart);
        String fromStatus = post.getCurrentStatus();
        String failedStatus = statechart == null ? null : failedStatus(statechart).orElse(null);
        if (!scheduledStatus.equals(fromStatus) && !(failedStatus != null && failedStatus.equals(fromStatus))) {
            String noun = statechart == null ? "Post" : statechart.noun();
            throw new BusinessException("This " + noun + " is " + statusLabel(statechart, fromStatus)
                    + ", so there is nothing to retry. Only a " + noun
                    + " that failed to publish, or one still waiting to, can be re-fired.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        for (PostPublishTarget target : failed) {
            target.setState(PostPublishTargetState.PENDING);
            target.setErrorMessage(null);
            target.setIdempotencyKey(freshIdempotencyKey(post.getId(), target));
            target.setFireTime(retryFireTime(target, now));
            targetRepository.save(target);
            log.info("Retrying target {} on {} for post {} under a fresh idempotency key, firing at {}",
                    target.getId(), target.getPlatform(), post.getId(), target.getFireTime());
        }

        if (!scheduledStatus.equals(fromStatus)) {
            post.setCurrentStatus(scheduledStatus);
            workItemRepository.save(post);
            log.info("Post {} moved {} -> {} while {} retried target(s) are in flight",
                    post.getId(), fromStatus, scheduledStatus, failed.size());
        }
        return new RetryResult(post, sorted(targetRepository.findAllByWorkItemId(post.getId())), failed.size());
    }

    /**
     * Resolves rows stranded in {@code PUBLISHING} — claimed for dispatch by a process that then died — into
     * {@code FAILED}, so they surface to a human instead of hanging forever.
     *
     * <p>Shares {@code conductor.post-publish.enabled} with {@code PostPublishScheduler}: this sweep is that
     * lane's own housekeeping, and its finder is globally scoped in exactly the same way, so it has to be off
     * wherever that poller is (local, and the test profile). Tests call {@link #runReconciliationTick} directly.
     */
    @Scheduled(fixedDelay = 300_000)
    public void reconcileStrandedTargets() {
        if (!reconciliationEnabled) {
            return;
        }
        runReconciliationTick(OffsetDateTime.now());
    }

    /**
     * {@link #strandedAfter}, read through the bean rather than off the field: this class is proxied for
     * {@code @Transactional}, and a test reading the field off the proxy would get the proxy's own
     * uninitialized copy.
     */
    Duration strandedAfter() {
        return strandedAfter;
    }

    /** One reconciliation pass. Package-private so tests drive it without flipping the config guard on. */
    int runReconciliationTick(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minus(strandedAfter);
        List<String> strandedIds = entityManager.createQuery(STRANDED_QUERY, String.class)
                .setParameter("cutoff", cutoff)
                .setMaxResults(reconcileBatchSize)
                .getResultList();

        int reconciled = 0;
        for (String targetId : strandedIds) {
            try {
                if (self.reconcileStrandedTarget(targetId, cutoff)) {
                    reconciled++;
                }
            } catch (Exception e) {
                log.error("Reconciling stranded publish target {} failed: {}", targetId, e.getMessage(), e);
            }
        }
        return reconciled;
    }

    /**
     * Moves one stranded row to {@code FAILED} with {@link #STRANDED_MESSAGE} and rolls its Post up.
     *
     * <p>The move is a conditional UPDATE re-asserting both {@code PUBLISHING} and the staleness cutoff, so a
     * genuine outcome that lands at the same moment wins and this updates nothing. It deliberately does not
     * go back to {@code PENDING}: the dispatch reached the platform, the post may be live, and neither poller
     * touches a {@code FAILED} row — getting it out again is an explicit human retry.
     *
     * @return true when this call is what moved the row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reconcileStrandedTarget(String targetId, OffsetDateTime cutoff) {
        PostPublishTarget target = targetRepository.findById(targetId).orElse(null);
        if (target == null || target.getState() != PostPublishTargetState.PUBLISHING) {
            return false;
        }
        String postId = target.getWorkItem() == null ? null : target.getWorkItem().getId();

        int moved = entityManager.createQuery(RECONCILE_QUERY)
                .setParameter("id", targetId)
                .setParameter("cutoff", cutoff)
                .setParameter("now", OffsetDateTime.now())
                .setParameter("message", STRANDED_MESSAGE)
                .executeUpdate();
        // The bulk update bypassed the persistence context, so drop the now-stale copy rather than let it
        // flush a PUBLISHING state back over the reconciliation.
        entityManager.detach(target);
        if (moved == 0) {
            return false;
        }

        log.warn("Publish target {} was stranded in PUBLISHING past {}; recorded as FAILED with an unknown"
                + " outcome — it will not be re-dispatched automatically", targetId, cutoff);
        rollUpPost(postId);
        return true;
    }

    /** The status a Post reaches when every target published — see {@link PublishingWorkflow#publishedStatus}. */
    static Optional<String> publishedStatus(Statechart statechart) {
        return PublishingWorkflow.publishedStatus(statechart);
    }

    /** The status a Post reaches when a target failed — see {@link PublishingWorkflow#failedStatus}. */
    static Optional<String> failedStatus(Statechart statechart) {
        return PublishingWorkflow.failedStatus(statechart);
    }

    /**
     * The status the Post waits in for its fire time, by its own Workflow; the legacy name when the
     * Workflow cannot be resolved, so a retry or a roll-up is never refused over a lookup failure.
     */
    private static String scheduledStatus(Statechart statechart) {
        return statechart == null ? PublishingWorkflow.LEGACY_SCHEDULED_STATUS
                : PublishingWorkflow.scheduledStatus(statechart).orElse(PublishingWorkflow.LEGACY_SCHEDULED_STATUS);
    }

    private void rollUp(PostPublishTarget target) {
        rollUpPost(target.getWorkItem() == null ? null : target.getWorkItem().getId());
    }

    /**
     * Re-reads the Post's whole set of targets and moves the Post on once none is still in flight: every
     * target published → the published status, anything else → the failed status.
     *
     * <p>A Workflow that cannot be resolved, or one that declares no published/failed status out of its
     * scheduled status, costs a log line and nothing else. The outcome already recorded is durable evidence
     * of something that happened on a platform, and it must survive whatever the roll-up cannot work out.
     */
    private void rollUpPost(String postId) {
        if (postId == null) {
            return;
        }
        WorkItem post = workItemRepository.findById(postId).orElse(null);
        if (post == null) {
            return;
        }
        Statechart statechart = statechartFor(post);
        String scheduledStatus = scheduledStatus(statechart);
        if (!scheduledStatus.equals(post.getCurrentStatus())) {
            return;
        }

        // A revoked destination is not an outcome — it was taken back down — so it is out of the tally.
        List<PostPublishTarget> counted = targetRepository.findAllByWorkItemId(postId).stream()
                .filter(target -> target.getState() != PostPublishTargetState.REVOKED)
                .toList();
        if (counted.isEmpty() || counted.stream().anyMatch(t -> IN_FLIGHT.contains(t.getState()))) {
            return;
        }
        boolean everyTargetPublished = counted.stream()
                .allMatch(t -> t.getState() == PostPublishTargetState.PUBLISHED);

        if (statechart == null) {
            log.warn("Post {} finished publishing but its workflow could not be resolved; status left at {}",
                    postId, post.getCurrentStatus());
            return;
        }
        Optional<String> toStatus = everyTargetPublished ? publishedStatus(statechart) : failedStatus(statechart);
        if (toStatus.isEmpty()) {
            log.warn("Post {} finished publishing ({} of {} targets published) but workflow {} declares no "
                            + "{} status out of {}; status left at {}",
                    postId, counted.stream().filter(t -> t.getState() == PostPublishTargetState.PUBLISHED).count(),
                    counted.size(), statechart.slug(), everyTargetPublished ? "published" : "failed",
                    scheduledStatus, post.getCurrentStatus());
            return;
        }

        String fromStatus = post.getCurrentStatus();
        post.setCurrentStatus(toStatus.get());
        workItemRepository.save(post);
        log.info("Post {} rolled up {} -> {}: {} of {} targets published",
                postId, fromStatus, toStatus.get(),
                counted.stream().filter(t -> t.getState() == PostPublishTargetState.PUBLISHED).count(),
                counted.size());

        // The roll-up is a status change like any other, so it has to announce itself like any other.
        // Without this the one outcome anybody actually waits for — did it go out, or did it fail? — was
        // the only status change in the product that notified nobody, and the Discord channel went quiet
        // at exactly the moment it mattered. Best-effort: a chat webhook being down must never roll back
        // a status that reflects what a platform has already done.
        try {
            workItemService.publishStatusChanged(
                    post.getProject().getId(), post, fromStatus, toStatus.get(), null);
        } catch (Exception e) {
            log.warn("Post {} rolled up to {} but the status-changed event could not be published: {}",
                    postId, toStatus.get(), e.getMessage());
        }
    }

    /**
     * The Post's version-pinned Statechart, or null when it cannot be resolved. Deliberately the optional
     * form of the resolver: the roll-up runs inside the outcome record and must never throw out of it.
     */
    private Statechart statechartFor(WorkItem post) {
        if (post == null || post.getProject() == null) {
            return null;
        }
        String slug = post.getWorkflow() != null ? post.getWorkflow() : WorkItemWorkflowService.DEFAULT_WORKFLOW;
        return resolver.resolve(post.getProject().getId(), slug, post.getWorkflowVersion()).orElse(null);
    }

    private static String statusLabel(Statechart statechart, String statusId) {
        if (statechart == null) {
            return statusId;
        }
        return statechart.status(statusId).map(s -> s.displayLabel()).orElse(statusId);
    }

    /**
     * A brand-new at-most-once anchor for a retried target, in the same shape
     * {@code PublishTargetService} mints at selection time so operators read one vocabulary. The random
     * suffix is the whole point: the column is uniquely constrained and the invocation layer is
     * claim-or-return on it, so only a genuinely new key can reach the platform again.
     */
    private static String freshIdempotencyKey(String postId, PostPublishTarget target) {
        return "pub:" + postId + ":" + target.getPlatform() + ":" + target.getConnectionId()
                + ":" + UUID.randomUUID();
    }

    /**
     * When a retried target should fire. An app-managed row keeps its own time (a past one is simply due
     * now); a native row is pushed out past the platform's minimum lead so the hand-off sweep will accept
     * it instead of refusing it as too soon and leaving it PENDING forever.
     */
    private static OffsetDateTime retryFireTime(PostPublishTarget target, OffsetDateTime now) {
        if (target.getLane() == PublishLane.NATIVE) {
            OffsetDateTime earliest = now.plus(NATIVE_RETRY_LEAD);
            return target.getFireTime() == null || target.getFireTime().isBefore(earliest)
                    ? earliest : target.getFireTime();
        }
        return target.getFireTime() == null ? now : target.getFireTime();
    }

    private static List<PostPublishTarget> sorted(List<PostPublishTarget> targets) {
        return targets.stream()
                .sorted(Comparator.comparing(PostPublishTarget::getPlatform)
                        .thenComparing(PostPublishTarget::getConnectionId))
                .toList();
    }

    /** The Asset type a published destination on {@code platform} becomes, or null if unrecognised. */
    String assetTypeFor(String platform) {
        PublishPlatform outcome = platformFor(platform);
        return outcome == null ? null : outcome.assetType();
    }

    /**
     * Whether a platform's error text describes a permanent auth/permission failure — an expired token, a
     * revoked grant, a missing scope — rather than something worth trying again. Conservative by design:
     * anything it does not recognise is treated as transient and leaves the connection's health alone.
     */
    static boolean isPermanentAuthFailure(String errorMessage) {
        return errorMessage != null && PERMANENT_AUTH_FAILURE.matcher(errorMessage).find();
    }

    private boolean applySuccess(PostPublishTarget target, String platformPostId, String permalink) {
        if (target.getState() == PostPublishTargetState.REVOKED) {
            log.warn("Publish succeeded for target {} but it was already revoked; not recording it as published",
                    target.getId());
            return false;
        }
        if (target.getState() == PostPublishTargetState.PUBLISHED) {
            // A duplicate result. The Asset write below is idempotent on (workItem, type, ref), so
            // re-running it converges rather than doubling; the row itself is left untouched.
            recordDestinationAsset(target, permalink);
            log.debug("Target {} is already PUBLISHED; outcome re-applied without moving the row", target.getId());
            return false;
        }

        target.setState(PostPublishTargetState.PUBLISHED);
        if (platformPostId != null && !platformPostId.isBlank()) {
            target.setPlatformPostId(platformPostId);
        }
        if (permalink != null && !permalink.isBlank()) {
            target.setPermalink(permalink);
        }
        target.setErrorMessage(null);
        targetRepository.save(target);

        recordDestinationAsset(target, permalink);
        log.info("Target {} published on {} (platform post {})",
                target.getId(), target.getPlatform(), target.getPlatformPostId());
        rollUp(target);
        return true;
    }

    private boolean applyFailure(PostPublishTarget target, String errorMessage, boolean permanentAuthFailure) {
        if (target.getState() == PostPublishTargetState.REVOKED) {
            log.debug("Target {} was revoked; a late failure does not overwrite it", target.getId());
            return false;
        }
        if (target.getState() == PostPublishTargetState.PUBLISHED) {
            log.warn("Target {} is already PUBLISHED; ignoring a late failure: {}", target.getId(), errorMessage);
            return false;
        }

        target.setState(PostPublishTargetState.FAILED);
        target.setErrorMessage(errorMessage);
        target.setAttempts(target.getAttempts() + 1);
        targetRepository.save(target);

        log.warn("Target {} failed to publish on {}: {}", target.getId(), target.getPlatform(), errorMessage);
        if (permanentAuthFailure) {
            connectionHealthService.reportPublishAuthFailure(target.getConnectionId(), errorMessage);
        }
        rollUp(target);
        return true;
    }

    /**
     * One typed Asset per published destination, named for the account it went to. Delegates the
     * {@code (workItem, type, ref)} idempotency guard to {@link AssetService#recordAsset} rather than
     * re-implementing it, so a duplicate outcome is a no-op wherever it comes from.
     */
    private void recordDestinationAsset(PostPublishTarget target, String permalink) {
        if (permalink == null || permalink.isBlank()) {
            log.warn("Target {} published on {} without a permalink; no Asset recorded",
                    target.getId(), target.getPlatform());
            return;
        }
        PublishPlatform platform = platformFor(target.getPlatform());
        if (platform == null) {
            log.warn("Target {} names platform '{}', which has no Asset type; no Asset recorded",
                    target.getId(), target.getPlatform());
            return;
        }
        WorkItem post = target.getWorkItem();
        if (post == null) {
            log.warn("Target {} has no owning Post; no Asset recorded", target.getId());
            return;
        }
        assetService.recordAsset(post, platform.assetType(), permalink,
                accountLabel(target, platform), AssetService.KIND_LINK);
    }

    /** The account a human would recognise this destination by, falling back to the platform's name. */
    private static String accountLabel(PostPublishTarget target, PublishPlatform platform) {
        String label = target.getPlatformAccountLabel();
        return label != null && !label.isBlank() ? label : platform.label();
    }

    private PublishPlatform platformFor(String platform) {
        return platformRegistry.find(platform).orElse(null);
    }

    private static String stringValue(Map<String, Object> output, String key) {
        Object value = output.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private PostPublishTarget find(String targetId) {
        PostPublishTarget target = targetRepository.findById(targetId).orElse(null);
        if (target == null) {
            log.warn("Publish outcome for target {} discarded — no such target", targetId);
        }
        return target;
    }
}
