package com.conductor.workflow;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ActionResult;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.ActiveConnectionResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The due-post poller for the {@link PublishLane#APP_MANAGED} lane (COND-23): every 30 seconds it looks
 * for {@code post_publish_target} rows Conductor still holds whose fire time has arrived, claims each
 * one, and hands it to {@link ActionInvocationService} to actually go out. Deliberately a separate
 * {@code @Component} from {@link WorkflowScheduler} — it mirrors that class's poll shape but shares
 * none of its concerns.
 *
 * <h2>At most once per (post, target)</h2>
 * This is the load-bearing property, and it rests on three things, in order:
 *
 * <ol>
 *   <li><b>The claim is a conditional UPDATE, not a read-then-write.</b> {@link #claimInNewTx} moves the
 *       row {@code PENDING -> PUBLISHING} with a single statement whose {@code WHERE} clause re-asserts
 *       {@code PENDING}, in its own {@code REQUIRES_NEW} transaction. Two ticks racing the same row both
 *       read {@code PENDING}, but the second one's UPDATE blocks on the first's row lock and then
 *       re-evaluates its predicate against the committed row — it updates zero rows and dispatches
 *       nothing. A plain "load, check, save" would let both through under READ COMMITTED.</li>
 *   <li><b>Only {@code PENDING} is ever dispatched.</b> {@code PUBLISHING}, {@code PUBLISHED},
 *       {@code HANDED_OFF} and {@code REVOKED} rows are skipped, so a scheduler restart that leaves a row
 *       mid-flight in {@code PUBLISHING} republishes nothing. Recovering such a row is a deliberate
 *       operator/retry decision, not something a restart does silently.</li>
 *   <li><b>The row's stored {@code idempotency_key} is passed through unchanged.</b>
 *       {@code ActionInvocationService} is claim-or-return on that key, and the column is uniquely
 *       constrained, so even a dispatch that somehow got past the first two guards cannot reach the
 *       platform twice.</li>
 * </ol>
 *
 * <p>The connector call deliberately happens <em>outside</em> the claim transaction, matching
 * {@link com.conductor.integration.ingest.ConnectorFeedScheduler}: a slow platform upload must never hold
 * a row lock or a pool transaction open.
 *
 * <h2>Lane ownership</h2>
 * NATIVE-lane rows are not this poller's business — those posts are handed to the platform's own
 * scheduler by a separate sweep, and are ignored here both by the repository's due query and by an
 * explicit guard in the claim.
 *
 * <h2>Why the config guard</h2>
 * The due query is globally scoped: it selects every due row in the database, not a project's. Left live
 * in the test profile it would claim rows another test just inserted — the same hazard that keeps
 * {@code ConnectorFeedScheduler} off by default there. Hence
 * {@code conductor.post-publish.enabled}: {@code true} in {@code application.properties}, {@code false}
 * in {@code application-local.properties} and {@code src/test/resources/application.properties}. Tests
 * that need the behavior call {@link #runTick(OffsetDateTime)} directly.
 */
@Component
public class PostPublishScheduler {

    private static final Logger log = LoggerFactory.getLogger(PostPublishScheduler.class);

    /**
     * The Work Item status a Post must sit in for this poller to publish it.
     *
     * <p><b>This is the one hardcoded status string in the publishing pipeline, and it is here on
     * purpose.</b> The lifecycle schema has no first-class way for a Workflow to declare "this is the
     * status an item waits in for its fire time" — {@code StatechartStatus} carries only
     * {@code id}/{@code label}/{@code category}/{@code initial}/{@code terminal}, none of which
     * identifies it, and MARKETING's {@code SCHEDULED} is distinguishable from its neighbours only by
     * name. When the schema grows such a declaration (the natural sibling of the {@code asset_types}
     * heuristic {@code PostScheduleValidator} documents), replace the single use of this constant in
     * {@link #ownerIsScheduled} with a read of that field; nothing else here changes.
     */
    static final String SCHEDULED_STATUS = "SCHEDULED";

    /** The publish action a platform goes out through, and the parameter its copy travels in. */
    record PublishAction(String actionId, String captionParam) {}

    /**
     * Platform to publish action, keyed by the {@code post_publish_target.platform} vocabulary and valued
     * from the connectors' own shipped tool specs ({@code meta.json}, {@code youtube.json},
     * {@code tiktok.json}).
     */
    static final Map<String, PublishAction> PUBLISH_ACTIONS = Map.of(
            "facebook", new PublishAction("publish_facebook_post", "message"),
            "instagram", new PublishAction("publish_instagram_media", "caption"),
            "youtube", new PublishAction("publish_video", "description"),
            "tiktok", new PublishAction("publish_video", "title"));

    /**
     * Claims one row for this tick. The {@code state = PENDING} predicate is the whole point: it is
     * re-evaluated under the row lock, so the loser of a race updates nothing. {@code updatedAt} is set
     * explicitly because {@code @PreUpdate} does not fire for a bulk update.
     */
    private static final String CLAIM_QUERY = """
            UPDATE PostPublishTarget t
               SET t.state = com.conductor.entity.PostPublishTargetState.PUBLISHING,
                   t.updatedAt = :now
             WHERE t.id = :id
               AND t.state = com.conductor.entity.PostPublishTargetState.PENDING
               AND t.lane = com.conductor.entity.PublishLane.APP_MANAGED
            """;

    /**
     * Bounds one tick. The repository's due finder is unbounded (it takes no {@code Pageable}), so the
     * cap is applied here; anything not reached is picked up 30 seconds later. Package-private (not
     * final) so a test can shrink it — same pattern as {@code ConnectorFeedScheduler#batchSize}.
     */
    int batchSize = 50;

    /**
     * Test seam, run inside {@link #claimInNewTx} between reading the row and issuing the conditional
     * UPDATE. It exists so a test can hold two claim transactions open past the point where both have
     * seen {@code PENDING} — the only way to prove the claim is genuinely atomic rather than merely
     * serialized by luck. A no-op in production.
     */
    Runnable beforeClaimUpdate = () -> { };

    private final PostPublishTargetRepository targetRepository;
    private final ActiveConnectionResolver connectionResolver;
    private final ActionInvocationService actionInvocationService;
    private final boolean enabled;

    /**
     * The transaction-bound shared {@code EntityManager}, used only to issue the conditional claim
     * UPDATE — the one thing {@code PostPublishTargetRepository} does not expose and that this task must
     * not add to it. Injected as a field rather than through the constructor because that is the
     * supported form of {@code @PersistenceContext}; package-private so unit tests can supply a stub.
     */
    @PersistenceContext
    EntityManager entityManager;

    /** Self-reference so the {@code REQUIRES_NEW} claim runs through the Spring proxy. */
    @Autowired
    @Lazy
    PostPublishScheduler self;

    public PostPublishScheduler(PostPublishTargetRepository targetRepository,
                                ActiveConnectionResolver connectionResolver,
                                ActionInvocationService actionInvocationService,
                                @Value("${conductor.post-publish.enabled:true}") boolean enabled) {
        this.targetRepository = targetRepository;
        this.connectionResolver = connectionResolver;
        this.actionInvocationService = actionInvocationService;
        this.enabled = enabled;
    }

    /**
     * A 30-second tick, comfortably inside COND-23's 60-second publishing SLO: a target due at 09:00 is
     * dispatched no later than 09:00:30.
     */
    @Scheduled(fixedDelay = 30_000)
    public void poll() {
        if (!enabled) {
            return;
        }
        runTick(OffsetDateTime.now());
    }

    /**
     * One poll pass. Package-private so tests drive it directly rather than flipping the config guard on
     * and letting a live tick loose against a shared test database.
     */
    void runTick(OffsetDateTime now) {
        List<String> dueIds = targetRepository.findDueAppManagedTargets(now).stream()
                .map(PostPublishTarget::getId)
                .limit(batchSize)
                .toList();

        for (String targetId : dueIds) {
            try {
                claimAndDispatch(targetId);
            } catch (Exception e) {
                log.error("Post publish dispatch failed for target {}: {}", targetId, e.getMessage(), e);
            }
        }
    }

    private void claimAndDispatch(String targetId) {
        PublishDispatch dispatch = self.claimInNewTx(targetId);
        if (dispatch == null) {
            return;
        }
        log.info("Dispatching publish for target {} (post {}, platform {}) via action {} on connection {}",
                dispatch.targetId(), dispatch.postId(), dispatch.platform(), dispatch.actionId(),
                dispatch.connection().getId());

        ActionResult result = actionInvocationService.invoke(dispatch.connection(), dispatch.actionId(),
                dispatch.input(), dispatch.idempotencyKey(), List.of());

        if (result != null && !result.success()) {
            // The row stays PUBLISHING: a failed dispatch is never silently re-queued, because the
            // platform may well have accepted the post before failing to tell us so.
            log.warn("Publish dispatch for target {} did not succeed: {}", dispatch.targetId(), result.message());
        }
    }

    /** Everything the dispatch needs, read while the claim transaction was still open. */
    record PublishDispatch(String targetId, String postId, String platform, Connection connection,
                           String actionId, Map<String, Object> input, String idempotencyKey) {}

    /**
     * Moves one due row {@code PENDING -> PUBLISHING} and returns what it takes to publish it, or
     * {@code null} when this tick must not publish it — the row is gone, is not this lane's, is no longer
     * {@code PENDING}, its Post is not in its scheduled status, its platform or connection cannot be
     * resolved, or another tick claimed it first.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PublishDispatch claimInNewTx(String targetId) {
        PostPublishTarget target = targetRepository.findById(targetId).orElse(null);
        if (target == null) {
            log.debug("Post publish target {} vanished between the due query and the claim", targetId);
            return null;
        }
        if (target.getLane() != PublishLane.APP_MANAGED) {
            log.debug("Post publish target {} is on the {} lane; not this poller's to publish",
                    targetId, target.getLane());
            return null;
        }
        if (target.getState() != PostPublishTargetState.PENDING) {
            log.debug("Post publish target {} is already {}; skipping", targetId, target.getState());
            return null;
        }

        WorkItem post = target.getWorkItem();
        if (!ownerIsScheduled(post)) {
            log.debug("Post publish target {} skipped: its post is in status {}, not {}",
                    targetId, post == null ? "(none)" : post.getCurrentStatus(), SCHEDULED_STATUS);
            return null;
        }

        PublishAction action = publishActionFor(target.getPlatform());
        if (action == null) {
            log.warn("Post publish target {} names platform '{}', which has no publish action; skipping",
                    targetId, target.getPlatform());
            return null;
        }

        String projectId = post.getProject() == null ? null : post.getProject().getId();
        Optional<Connection> connection = connectionResolver.resolveById(projectId, target.getConnectionId());
        if (connection.isEmpty()) {
            log.warn("Post publish target {} names connection {}, which is not available; skipping",
                    targetId, target.getConnectionId());
            return null;
        }

        PublishDispatch dispatch = new PublishDispatch(targetId, post.getId(), target.getPlatform(),
                connection.get(), action.actionId(), buildInput(target, post, action),
                target.getIdempotencyKey());

        beforeClaimUpdate.run();

        int claimed = entityManager.createQuery(CLAIM_QUERY)
                .setParameter("id", targetId)
                .setParameter("now", OffsetDateTime.now())
                .executeUpdate();
        if (claimed == 0) {
            log.info("Post publish target {} was claimed by another tick; not dispatching", targetId);
            return null;
        }
        // The bulk update bypassed the persistence context, so drop the now-stale copy rather than let
        // it flush a PENDING state back over the claim.
        entityManager.detach(target);

        log.info("Claimed post publish target {} (post {}, platform {}, connection {}) for publishing",
                targetId, post.getId(), target.getPlatform(), target.getConnectionId());
        return dispatch;
    }

    private boolean ownerIsScheduled(WorkItem post) {
        return post != null && SCHEDULED_STATUS.equals(post.getCurrentStatus());
    }

    private PublishAction publishActionFor(String platform) {
        if (platform == null) {
            return null;
        }
        return PUBLISH_ACTIONS.get(platform.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The publish payload: the copy that goes out, plus the two handles the platform executor resolves
     * this post's media from. Media parameters themselves ({@code image_url}, {@code asset_ref},
     * {@code asset_id}) are the per-platform publisher's to fill in — this poller's job ends at
     * dispatching the right action, on the right connection, exactly once.
     */
    private Map<String, Object> buildInput(PostPublishTarget target, WorkItem post, PublishAction action) {
        Map<String, Object> input = new LinkedHashMap<>();
        String caption = target.getCaptionOverride() != null && !target.getCaptionOverride().isBlank()
                ? target.getCaptionOverride()
                : post.getDescription();
        if (caption != null) {
            input.put(action.captionParam(), caption);
        }
        if (post.getTitle() != null && !"title".equals(action.captionParam())) {
            input.put("title", post.getTitle());
        }
        input.put("work_item_id", post.getId());
        input.put("target_id", target.getId());
        return input;
    }
}
