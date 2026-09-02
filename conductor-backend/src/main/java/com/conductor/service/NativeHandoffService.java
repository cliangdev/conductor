package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.integration.ActionResult;
import com.conductor.repository.PostPublishTargetRepository;
import jakarta.persistence.EntityManager;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link PublishLane#NATIVE} lane (COND-23): platforms that own scheduling themselves. Conductor hands
 * Facebook or YouTube the asset plus a schedule parameter and the platform fires it — which is exactly why
 * this class also owns <em>taking it back</em>.
 *
 * <h2>The safety property</h2>
 * Once a target is {@link PostPublishTargetState#HANDED_OFF} the post <b>exists on the platform</b>. From
 * that moment nothing Conductor does to the Work Item can stop it going live except an explicit revocation
 * against the platform. So <b>every exit from the scheduled status must revoke first</b>: unschedule, an
 * edit that reverts the item, and delete all run {@link #unschedule(WorkItem)}, and it deliberately runs
 * inside the caller's transaction (default {@code REQUIRED} propagation, connector call included) so a
 * revocation that fails throws and rolls the status change back rather than leaving a live scheduled post
 * behind an item that no longer says it is scheduled. This is the one place in the publishing pipeline
 * where holding a transaction across connector I/O is the correct trade — the alternative is a post that
 * goes live after a human unscheduled it.
 *
 * <h2>Hand-off, and why some of it is deferred</h2>
 * Platforms only accept a schedule parameter inside a window measured from the API request:
 *
 * <ul>
 *   <li><b>Facebook</b> — {@code scheduled_publish_time} must be between ten minutes and thirty days out.</li>
 *   <li><b>YouTube</b> — {@code publish_at} on a private upload has no far-future limit.</li>
 * </ul>
 *
 * A Facebook target further out than thirty days therefore cannot be handed off when its Post enters the
 * scheduled status; it stays {@code PENDING} and {@link #sweepDeferredHandoffs()} completes the hand-off
 * once its fire time comes inside the window, so the post still goes live at the time the human chose.
 *
 * <h2>At most once per (post, target)</h2>
 * Same discipline as {@link com.conductor.workflow.PostPublishScheduler}, for the same reason: the claim is
 * a conditional bulk UPDATE re-asserting {@code PENDING} (and {@code NATIVE}) in its own
 * {@code REQUIRES_NEW} transaction, so two ticks racing a row both read {@code PENDING} but only one
 * updates it. The connector call happens outside that transaction. The row's stored
 * {@code idempotency_key} is passed through unchanged, so {@link ActionInvocationService}'s claim-or-return
 * is the backstop even if the first two guards were somehow bypassed.
 *
 * <h2>Lane ownership</h2>
 * {@link PublishLane#APP_MANAGED} rows belong to {@code PostPublishScheduler} and are untouched by every
 * path here — the repository's native finder excludes them, and each entry point re-checks the lane.
 *
 * <h2>Why the config guard</h2>
 * {@link #runTick(OffsetDateTime)}'s finder is globally scoped, exactly like the app-managed poller's, so a
 * live tick in the test profile would hand off rows another test just inserted. Hence
 * {@code conductor.native-handoff.enabled}: {@code true} in {@code application.properties}, {@code false}
 * in {@code application-local.properties} and {@code src/test/resources/application.properties}. Tests call
 * {@link #runTick(OffsetDateTime)} directly.
 */
@Service
public class NativeHandoffService {

    private static final Logger log = LoggerFactory.getLogger(NativeHandoffService.class);

    /**
     * The Work Item status a Post must sit in for the deferred sweep to hand it off. Same hardcoded string,
     * for the same reason, as {@code PostPublishScheduler.SCHEDULED_STATUS} — the lifecycle schema has no
     * first-class way for a Workflow to declare "this is the status an item waits in for its fire time".
     * When it grows one, both uses become a read of that field.
     */
    static final String SCHEDULED_STATUS = "SCHEDULED";

    /** Facebook refuses a {@code scheduled_publish_time} more than thirty days from the API request. */
    static final Duration FACEBOOK_MAX_LEAD = Duration.ofDays(30);

    /**
     * How far from "now" a platform will accept a scheduled post. A {@code null} {@link #maxLead} means the
     * platform declares no far-future limit (YouTube), not "zero".
     */
    record HandoffWindow(Duration minLead, Duration maxLead) {

        boolean accepts(OffsetDateTime now, OffsetDateTime fireTime) {
            return fireTime != null && !tooSoon(now, fireTime) && !tooFarOut(now, fireTime);
        }

        boolean tooSoon(OffsetDateTime now, OffsetDateTime fireTime) {
            return fireTime != null && fireTime.isBefore(now.plus(minLead));
        }

        boolean tooFarOut(OffsetDateTime now, OffsetDateTime fireTime) {
            return maxLead != null && fireTime != null && fireTime.isAfter(now.plus(maxLead));
        }
    }

    /**
     * Everything this service needs to know about one native platform: how it is told to schedule a post,
     * where it reports the id of what it created, and how that creation is taken back.
     *
     * <p>The action ids and parameter names are the connectors' own shipped vocabulary
     * ({@code meta.json}, {@code youtube.json}); the revoke actions are the counterparts the platform
     * publishers implement.
     */
    record NativePlatform(String publishActionId,
                          String scheduleParam,
                          String captionParam,
                          Map<String, Object> publishExtras,
                          String postIdOutputKey,
                          String revokeActionId,
                          String revokeIdParam,
                          Map<String, Object> revokeExtras,
                          List<String> clearedOnRevoke,
                          HandoffWindow window) {}

    /**
     * The native lane, keyed by the {@code post_publish_target.platform} vocabulary.
     *
     * <p>Facebook reschedules are always cancel-and-recreate — the Graph API's editing of an already
     * scheduled post is too limited to rely on — so revocation is a plain DELETE of the stored post id.
     * YouTube's is a re-privatization with {@code publish_at} cleared, which strands the upload harmlessly
     * instead of destroying a video a human may still want.
     */
    static final Map<String, NativePlatform> NATIVE_PLATFORMS = Map.of(
            "facebook", new NativePlatform(
                    "publish_facebook_post", "scheduled_publish_time", "message", Map.of(),
                    "post_id",
                    "delete_facebook_post", "post_id", Map.of(), List.of(),
                    new HandoffWindow(PostScheduleValidator.MINIMUM_LEAD_TIME, FACEBOOK_MAX_LEAD)),
            "youtube", new NativePlatform(
                    "publish_video", "publish_at", "description", Map.of("privacy_status", "private"),
                    "video_id",
                    "unpublish_video", "video_id", Map.of("privacy_status", "private"), List.of("publish_at"),
                    new HandoffWindow(Duration.ZERO, null)));

    /**
     * The claim: {@code PENDING -> HANDED_OFF} in one statement whose {@code WHERE} re-asserts both the
     * state and the lane, so a racing tick updates zero rows and hands nothing to the platform.
     * {@code updatedAt} is set explicitly because {@code @PreUpdate} does not fire for a bulk update.
     */
    private static final String CLAIM_QUERY = """
            UPDATE PostPublishTarget t
               SET t.state = com.conductor.entity.PostPublishTargetState.HANDED_OFF,
                   t.updatedAt = :now
             WHERE t.id = :id
               AND t.state = com.conductor.entity.PostPublishTargetState.PENDING
               AND t.lane = com.conductor.entity.PublishLane.NATIVE
            """;

    /**
     * Records what the platform created, but only while the row is still {@code HANDED_OFF}. If a
     * revocation landed while the platform call was in flight this updates zero rows, and the caller
     * immediately revokes the post it just created rather than writing a live platform id back over a
     * {@code REVOKED} row.
     */
    private static final String COMPLETE_QUERY = """
            UPDATE PostPublishTarget t
               SET t.platformPostId = :platformPostId,
                   t.errorMessage = NULL,
                   t.updatedAt = :now
             WHERE t.id = :id
               AND t.state = com.conductor.entity.PostPublishTargetState.HANDED_OFF
            """;

    /**
     * Same claim-preserving completion, for a platform that also returned a permalink. Split rather than
     * bound with a nullable parameter because Hibernate cannot infer a type for a {@code null} bulk-update
     * parameter.
     */
    private static final String COMPLETE_WITH_PERMALINK_QUERY = """
            UPDATE PostPublishTarget t
               SET t.platformPostId = :platformPostId,
                   t.permalink = :permalink,
                   t.errorMessage = NULL,
                   t.updatedAt = :now
             WHERE t.id = :id
               AND t.state = com.conductor.entity.PostPublishTargetState.HANDED_OFF
            """;

    /** Bounds one sweep; anything not reached is picked up on the next tick. Package-private for tests. */
    int batchSize = 50;

    /**
     * How far ahead the sweep's finder looks. Deliberately far out rather than Facebook's thirty days:
     * YouTube declares no far-future limit, so a bound of thirty days would strand a YouTube target whose
     * hand-off on entering the scheduled status failed. The per-platform {@link HandoffWindow} is what
     * actually decides; the finder bound only caps the scan.
     */
    Duration sweepHorizon = Duration.ofDays(3650);

    private final PostPublishTargetRepository targetRepository;
    private final ActiveConnectionResolver connectionResolver;
    private final ActionInvocationService actionInvocationService;
    private final PublishOutcomeService publishOutcomeService;
    private final boolean enabled;

    /**
     * Transaction-bound shared {@code EntityManager}, used only for the two conditional bulk updates the
     * repository does not expose. Field-injected because that is the supported form of
     * {@code @PersistenceContext}; package-private so unit tests can supply a stub.
     */
    @PersistenceContext
    EntityManager entityManager;

    /** Self-reference so the {@code REQUIRES_NEW} helpers run through the Spring proxy. */
    @Autowired
    @Lazy
    NativeHandoffService self;

    public NativeHandoffService(PostPublishTargetRepository targetRepository,
                                ActiveConnectionResolver connectionResolver,
                                ActionInvocationService actionInvocationService,
                                PublishOutcomeService publishOutcomeService,
                                @Value("${conductor.native-handoff.enabled:true}") boolean enabled) {
        this.targetRepository = targetRepository;
        this.connectionResolver = connectionResolver;
        this.actionInvocationService = actionInvocationService;
        this.publishOutcomeService = publishOutcomeService;
        this.enabled = enabled;
    }

    // ---- (a) hand-off on entering the scheduled status -----------------------------------------

    /**
     * Hands every native target of {@code post} whose fire time is inside its platform's window to that
     * platform, storing what the platform created and moving the row to {@code HANDED_OFF}. Targets outside
     * the window (a Facebook post more than thirty days out) are left {@code PENDING} for
     * {@link #sweepDeferredHandoffs()}, and {@code APP_MANAGED} rows are ignored entirely.
     *
     * <p>Call this on the transition <em>into</em> the scheduled status, after the item has been saved. One
     * target's failure is logged and never blocks the rest — the same isolation the app-managed poller
     * gives a batch.
     */
    public void handoffForPost(WorkItem post) {
        if (post == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (PostPublishTarget target : nativeTargets(post.getId(), PostPublishTargetState.PENDING)) {
            try {
                handoffTarget(target.getId(), now, false);
            } catch (Exception e) {
                log.error("Native hand-off failed for target {} (post {}): {}",
                        target.getId(), post.getId(), e.getMessage(), e);
            }
        }
    }

    // ---- (b) deferred hand-off -----------------------------------------------------------------

    /**
     * Completes hand-offs that could not happen when their Post was scheduled, because the fire time was
     * further out than the platform would accept. A minute's granularity is ample: the earliest a deferred
     * row can become eligible is Facebook's thirty-day boundary, never a fire time that is imminent.
     */
    @Scheduled(fixedDelay = 60_000)
    public void sweepDeferredHandoffs() {
        if (!enabled) {
            return;
        }
        runTick(OffsetDateTime.now());
    }

    /** One sweep pass. Package-private so tests drive it instead of flipping the config guard on. */
    void runTick(OffsetDateTime now) {
        List<String> ids = targetRepository.findNativeHandoffTargets(now.plus(sweepHorizon)).stream()
                .map(PostPublishTarget::getId)
                .limit(batchSize)
                .toList();

        for (String targetId : ids) {
            try {
                handoffTarget(targetId, now, true);
            } catch (Exception e) {
                log.error("Deferred native hand-off failed for target {}: {}", targetId, e.getMessage(), e);
            }
        }
    }

    // ---- (c) revocation ------------------------------------------------------------------------

    /**
     * Takes one handed-off target back off its platform and moves the row to {@code REVOKED}.
     *
     * <p>Idempotent by construction: an already-{@code REVOKED} row and a row with no
     * {@code platform_post_id} (nothing ever reached the platform) are no-ops rather than failures, and a
     * repeated revocation of the same platform post collapses onto one {@link ActionInvocationService}
     * idempotency key. {@code APP_MANAGED} rows are never touched.
     *
     * @throws BusinessException if the platform post exists but could not be taken back down — so a caller
     *                           inside a transaction rolls back rather than committing a status change that
     *                           leaves a live scheduled post behind
     */
    @Transactional
    public void revoke(PostPublishTarget target) {
        if (target == null || target.getState() == PostPublishTargetState.REVOKED) {
            return;
        }
        if (target.getLane() != PublishLane.NATIVE) {
            log.debug("Post publish target {} is on the {} lane; not this service's to revoke",
                    target.getId(), target.getLane());
            return;
        }

        String platformPostId = target.getPlatformPostId();
        if (platformPostId == null || platformPostId.isBlank()) {
            // Nothing ever reached the platform — there is no live post to take down, so this is a
            // state-only revocation, not a failure.
            log.info("Post publish target {} has no platform post id; revoking without a platform call",
                    target.getId());
        } else {
            revokeOnPlatform(target, platformPostId);
        }

        target.setState(PostPublishTargetState.REVOKED);
        targetRepository.save(target);
    }

    // ---- (d) unschedule ------------------------------------------------------------------------

    /**
     * Revokes every handed-off native target of {@code post}. This is the single path out of the scheduled
     * status — unschedule, an edit that reverts the item, and delete all run it.
     *
     * <p>Runs in the caller's transaction on purpose. A failed revocation throws, the caller's status change
     * never commits, and the Post stays scheduled — visibly consistent with the post that is still live on
     * the platform. Targets still {@code PENDING} are left alone: nothing was handed to a platform, and
     * marking them terminal would make the Post unschedulable a second time.
     */
    @Transactional
    public void unschedule(WorkItem post) {
        if (post == null) {
            return;
        }
        for (PostPublishTarget target : nativeTargets(post.getId(), PostPublishTargetState.HANDED_OFF)) {
            revoke(target);
        }
        standDownManualTargets(post);
    }

    /**
     * Puts every manual target that was waiting on a human back to {@code PENDING}.
     *
     * <p>{@code AWAITING_MANUAL} means "someone still has to post this". Once the Post leaves the scheduled
     * status that is no longer true, and leaving the row flagged would keep asking a human to publish a post
     * that has been pulled back — the manual-lane equivalent of leaving a scheduled post live on a platform,
     * except the platform here is a person. {@code PENDING} is the correct resting state: it is where a
     * not-yet-due target sits, and re-scheduling re-stamps and re-flags it in the ordinary way.
     *
     * <p>Only {@code AWAITING_MANUAL} rows are touched. A manual target already marked {@code PUBLISHED} by
     * a human describes a post that really is live and out of Conductor's reach — nothing here can take it
     * down, and quietly reverting the row would erase the only record that it exists.
     */
    private void standDownManualTargets(WorkItem post) {
        for (PostPublishTarget target : targetRepository.findAllByWorkItemIdAndState(
                post.getId(), PostPublishTargetState.AWAITING_MANUAL)) {
            if (target.getLane() != PublishLane.MANUAL) {
                continue;
            }
            target.setState(PostPublishTargetState.PENDING);
            targetRepository.save(target);
            log.info("Manual publish target {} stood down to PENDING; post {} is no longer scheduled",
                    target.getId(), post.getId());
        }
    }

    // ---- hand-off internals --------------------------------------------------------------------

    /** Everything the hand-off needs, read while the claim transaction was still open. */
    record HandoffDispatch(String targetId, String postId, String platform, Connection connection,
                           String actionId, Map<String, Object> input, String idempotencyKey) {}

    private void handoffTarget(String targetId, OffsetDateTime now, boolean requireScheduledStatus) {
        HandoffDispatch dispatch = self.claimInNewTx(targetId, now, requireScheduledStatus);
        if (dispatch == null) {
            return;
        }
        log.info("Handing off target {} (post {}, platform {}) to the platform scheduler via action {}",
                dispatch.targetId(), dispatch.postId(), dispatch.platform(), dispatch.actionId());

        ActionResult result = actionInvocationService.invoke(dispatch.connection(), dispatch.actionId(),
                dispatch.input(), dispatch.idempotencyKey(), List.of());

        if (result == null || !result.success()) {
            String message = result == null ? "Publish action returned no result" : result.message();
            log.warn("Native hand-off for target {} did not succeed: {}", dispatch.targetId(), message);
            // Terminal, not re-queued: the platform may well have accepted the post before failing to say so.
            // Recorded through the one place outcomes are filed, so a rejected hand-off gets the same
            // treatment as a rejected publish — including the connection health report a permanent auth
            // failure has to raise, which a plain state write cannot do.
            publishOutcomeService.recordFailure(dispatch.targetId(), message);
            return;
        }

        NativePlatform platform = NATIVE_PLATFORMS.get(dispatch.platform());
        String platformPostId = stringValue(result.output(), platform.postIdOutputKey());
        String permalink = stringValue(result.output(), "permalink");
        if (platformPostId == null) {
            log.warn("Platform accepted target {} but reported no '{}' — it cannot be revoked by id",
                    dispatch.targetId(), platform.postIdOutputKey());
            return;
        }

        boolean recorded = self.completeHandoffInNewTx(dispatch.targetId(), platformPostId, permalink);
        if (!recorded) {
            // The Post was unscheduled while the platform call was in flight. The scheduled post exists
            // now, and the row that would have remembered it is already REVOKED — take it back down here
            // or it goes live behind a Post that no longer says it is scheduled.
            log.warn("Target {} was revoked while its hand-off was in flight; revoking platform post {}",
                    dispatch.targetId(), platformPostId);
            invokeRevokeAction(dispatch.connection(), dispatch.platform(), platformPostId,
                    revokeKey(dispatch.idempotencyKey(), platformPostId));
        }
    }

    /**
     * Moves one target {@code PENDING -> HANDED_OFF} and returns what it takes to hand it to its platform,
     * or {@code null} when this pass must not hand it off — the row is gone, is not this lane's, is no
     * longer {@code PENDING}, its Post is not in its scheduled status, its platform is not a native one,
     * its fire time is outside the platform's window, its connection cannot be resolved, or another pass
     * claimed it first.
     *
     * <p>A target rejected only by the window is left exactly as it was, so a later sweep picks it up once
     * the fire time comes inside — that deferral is the whole reason the sweep exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HandoffDispatch claimInNewTx(String targetId, OffsetDateTime now, boolean requireScheduledStatus) {
        PostPublishTarget target = targetRepository.findById(targetId).orElse(null);
        if (target == null) {
            log.debug("Post publish target {} vanished before its hand-off", targetId);
            return null;
        }
        if (target.getLane() != PublishLane.NATIVE) {
            log.debug("Post publish target {} is on the {} lane; not this service's to hand off",
                    targetId, target.getLane());
            return null;
        }
        if (target.getState() != PostPublishTargetState.PENDING) {
            log.debug("Post publish target {} is already {}; skipping hand-off", targetId, target.getState());
            return null;
        }

        WorkItem post = target.getWorkItem();
        if (post == null) {
            log.warn("Post publish target {} has no owning post; skipping hand-off", targetId);
            return null;
        }
        if (requireScheduledStatus && !SCHEDULED_STATUS.equals(post.getCurrentStatus())) {
            log.debug("Post publish target {} skipped: its post is in status {}, not {}",
                    targetId, post.getCurrentStatus(), SCHEDULED_STATUS);
            return null;
        }

        NativePlatform platform = nativePlatformFor(target.getPlatform());
        if (platform == null) {
            log.warn("Post publish target {} names platform '{}', which has no native hand-off; skipping",
                    targetId, target.getPlatform());
            return null;
        }
        if (!platform.window().accepts(now, target.getFireTime())) {
            if (platform.window().tooFarOut(now, target.getFireTime())) {
                log.debug("Target {} fires at {}, beyond {}'s hand-off window; deferring",
                        targetId, target.getFireTime(), target.getPlatform());
            } else {
                log.warn("Target {} fires at {}, inside {}'s minimum lead time; it cannot be scheduled natively",
                        targetId, target.getFireTime(), target.getPlatform());
            }
            return null;
        }

        String projectId = post.getProject() == null ? null : post.getProject().getId();
        Optional<Connection> connection = connectionResolver.resolveById(projectId, target.getConnectionId());
        if (connection.isEmpty()) {
            log.warn("Post publish target {} names connection {}, which is not available; skipping hand-off",
                    targetId, target.getConnectionId());
            return null;
        }

        HandoffDispatch dispatch = new HandoffDispatch(targetId, post.getId(),
                normalizedPlatform(target.getPlatform()), connection.get(), platform.publishActionId(),
                buildHandoffInput(target, post, platform), target.getIdempotencyKey());

        int claimed = entityManager.createQuery(CLAIM_QUERY)
                .setParameter("id", targetId)
                .setParameter("now", OffsetDateTime.now())
                .executeUpdate();
        if (claimed == 0) {
            log.info("Post publish target {} was claimed by another pass; not handing off", targetId);
            return null;
        }
        // The bulk update bypassed the persistence context — drop the stale copy so it cannot flush
        // PENDING back over the claim.
        entityManager.detach(target);

        log.info("Claimed post publish target {} (post {}, platform {}) for native hand-off",
                targetId, post.getId(), target.getPlatform());
        return dispatch;
    }

    /**
     * Records the platform's id for the post it just created. Returns {@code false} when the row is no
     * longer {@code HANDED_OFF} — it was revoked mid-flight, and the caller must take the platform post
     * back down itself.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeHandoffInNewTx(String targetId, String platformPostId, String permalink) {
        var query = permalink == null
                ? entityManager.createQuery(COMPLETE_QUERY)
                : entityManager.createQuery(COMPLETE_WITH_PERMALINK_QUERY).setParameter("permalink", permalink);
        int updated = query
                .setParameter("platformPostId", platformPostId)
                .setParameter("now", OffsetDateTime.now())
                .setParameter("id", targetId)
                .executeUpdate();
        return updated > 0;
    }

    /**
     * The hand-off payload: the copy that goes out, the schedule parameter that makes the platform fire it,
     * and the two handles the platform publisher resolves this post's media from. Media parameters
     * themselves are the per-platform publisher's to fill in.
     */
    private Map<String, Object> buildHandoffInput(PostPublishTarget target, WorkItem post, NativePlatform platform) {
        Map<String, Object> input = new LinkedHashMap<>();
        String caption = target.getCaptionOverride() != null && !target.getCaptionOverride().isBlank()
                ? target.getCaptionOverride()
                : post.getDescription();
        if (caption != null) {
            input.put(platform.captionParam(), caption);
        }
        if (post.getTitle() != null && !"title".equals(platform.captionParam())) {
            input.put("title", post.getTitle());
        }
        input.putAll(platform.publishExtras());
        // Normalized to a UTC instant, not the stored offset's rendering: the platform is being told a
        // moment in time, and an offset-bearing string round-trips differently depending on where the row
        // was written. The Post's own timezone stays on the Work Item, for humans.
        input.put(platform.scheduleParam(), target.getFireTime().toInstant().toString());
        input.put("work_item_id", post.getId());
        input.put("target_id", target.getId());
        return input;
    }

    // ---- revocation internals ------------------------------------------------------------------

    private void revokeOnPlatform(PostPublishTarget target, String platformPostId) {
        String platform = normalizedPlatform(target.getPlatform());
        NativePlatform nativePlatform = nativePlatformFor(target.getPlatform());
        if (nativePlatform == null) {
            throw new BusinessException("Cannot revoke post publish target " + target.getId()
                    + ": platform '" + target.getPlatform() + "' has no native revoke action");
        }

        WorkItem post = target.getWorkItem();
        String projectId = post == null || post.getProject() == null ? null : post.getProject().getId();
        Connection connection = connectionResolver.resolveById(projectId, target.getConnectionId())
                .orElseThrow(() -> new BusinessException("Cannot revoke post publish target " + target.getId()
                        + ": its connection is not available, so the scheduled post cannot be taken down"));

        invokeRevokeAction(connection, platform, platformPostId,
                revokeKey(target.getIdempotencyKey(), platformPostId));
    }

    private void invokeRevokeAction(Connection connection, String platform, String platformPostId,
                                    String idempotencyKey) {
        NativePlatform nativePlatform = NATIVE_PLATFORMS.get(platform);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(nativePlatform.revokeIdParam(), platformPostId);
        input.putAll(nativePlatform.revokeExtras());
        // Explicit nulls, not omissions: clearing publishAt is the difference between a re-privatized
        // upload and one that quietly goes public at the time the human just cancelled.
        for (String cleared : nativePlatform.clearedOnRevoke()) {
            input.put(cleared, null);
        }

        ActionResult result = actionInvocationService.invoke(connection, nativePlatform.revokeActionId(),
                input, idempotencyKey, List.of());

        if (result == null || !result.success()) {
            String message = result == null ? "Revoke action returned no result" : result.message();
            throw new BusinessException("Could not revoke scheduled " + platform + " post " + platformPostId
                    + ": " + message);
        }
        log.info("Revoked scheduled {} post {}", platform, platformPostId);
    }

    /**
     * One revoke key per platform post, so a repeated revocation of the same post collapses onto the stored
     * result while a later cancel-and-recreate cycle's revocation gets a key of its own.
     */
    private String revokeKey(String targetIdempotencyKey, String platformPostId) {
        return "revoke:" + targetIdempotencyKey + ":" + platformPostId;
    }

    // ---- shared helpers ------------------------------------------------------------------------

    private List<PostPublishTarget> nativeTargets(String workItemId, PostPublishTargetState state) {
        return targetRepository.findAllByWorkItemIdAndState(workItemId, state).stream()
                .filter(t -> t.getLane() == PublishLane.NATIVE)
                .toList();
    }

    private NativePlatform nativePlatformFor(String platform) {
        String normalized = normalizedPlatform(platform);
        return normalized == null ? null : NATIVE_PLATFORMS.get(normalized);
    }

    private String normalizedPlatform(String platform) {
        return platform == null ? null : platform.trim().toLowerCase(Locale.ROOT);
    }

    private String stringValue(Map<String, Object> output, String key) {
        Object value = output == null ? null : output.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }
}
