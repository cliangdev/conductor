package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ActionResult;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.publish.PublishPlatform;
import com.conductor.service.publish.PublishPlatformRegistry;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The other half of the {@link PublishLane#NATIVE} lane (COND-23 T6.4): noticing that a post the platform
 * owns has actually gone live.
 *
 * <p>{@link NativeHandoffService} stops at {@link PostPublishTargetState#HANDED_OFF} — correctly, because
 * at that moment the post exists on the platform but has not published. Nothing else in the pipeline ever
 * moves such a row on: the app-managed lane publishes inline and records its own outcome, but a Facebook
 * page post scheduled for Tuesday simply goes live on Tuesday without telling Conductor. Without this
 * poller a native target would sit {@code HANDED_OFF} forever, never record its permalink Asset, and a
 * Post with only native targets would never reach Published.
 *
 * <p>So: for every handed-off native row whose fire time has passed, ask the platform whether the post is
 * live, and when it is, funnel the answer through the one place outcomes are recorded,
 * {@link PublishOutcomeService#recordSuccess} — the same call the app-managed lane makes, so a native
 * publish produces the same {@code facebook_post}/{@code youtube_video} Asset with the same permalink.
 *
 * <h2>Bounded, never forever</h2>
 * A post can fail to appear for reasons Conductor cannot see: the platform silently dropped it, a human
 * deleted it, the channel was suspended. Polling such a row every minute until the heat death of the
 * universe is not a design. Each pass burns one attempt, and the pass that reaches
 * {@link #maxConfirmationAttempts} without a confirmation resolves the row into {@code FAILED} with a
 * message naming the platform, the post id and what the last check saw — enough for a human to go look.
 *
 * <p>The attempt counter is the row's own {@code attempts} column, which is otherwise untouched between a
 * successful hand-off (which does not increment it) and this poller. Reusing it costs no migration and
 * means the same number a human sees on a failed publish is the number of times we asked.
 *
 * <h2>At most once</h2>
 * The attempt bump <em>is</em> the claim: a conditional bulk UPDATE re-asserting both
 * {@code state = HANDED_OFF} and the attempt count it read, in its own {@code REQUIRES_NEW} transaction.
 * Two ticks racing the same row both read the same count, but the second one's UPDATE re-evaluates its
 * predicate under the first's row lock, updates zero rows and asks the platform nothing. A {@code REVOKED},
 * {@code PUBLISHED} or {@code FAILED} row fails that predicate too, so a terminal row is never polled —
 * and {@link PublishOutcomeService} refuses to overwrite those states in any case.
 *
 * <p>Each attempt reads under an idempotency key of its own ({@code confirm:<key>:<attempt>}). That is not
 * optional: {@link ActionInvocationService} is claim-or-return on the key, so a reused key would hand back
 * the first check's "not yet" forever instead of asking again.
 *
 * <h2>Why the config guard</h2>
 * The due query is globally scoped — every handed-off native row in the database, not a project's — so a
 * live tick in the test profile would poll rows another test just inserted. Hence
 * {@code conductor.native-publish-confirmation.enabled}: {@code true} in {@code application.properties},
 * {@code false} in {@code application-local.properties} and {@code src/test/resources/application.properties}.
 * Tests call {@link #runTick(OffsetDateTime)} directly.
 */
@Service
public class NativePublishConfirmationPoller {

    private static final Logger log = LoggerFactory.getLogger(NativePublishConfirmationPoller.class);

    /*
     * How each native platform is asked whether a post went live — the read action's id, the parameter the
     * stored platform post id travels in, the key it is reported back under, and how the answer is read as
     * "live yet?" — is the platform's PublishPlatform.ConfirmAction in the registry; the readers themselves
     * are PlatformLiveness.
     */

    /** The output key every action reports a post's public URL under. */
    private static final String PERMALINK_OUTPUT_KEY = "permalink";

    /**
     * The due finder. Not on {@link PostPublishTargetRepository} because this is the only caller and the
     * repository is not this task's to grow; issued through the shared {@code EntityManager} the same way
     * the two schedulers issue their claims.
     */
    private static final String DUE_QUERY = """
            SELECT t.id FROM PostPublishTarget t
             WHERE t.lane = com.conductor.entity.PublishLane.NATIVE
               AND t.state = com.conductor.entity.PostPublishTargetState.HANDED_OFF
               AND t.fireTime <= :now
             ORDER BY t.fireTime ASC
            """;

    /**
     * The claim: one attempt, taken atomically. The {@code attempts = :attempts} predicate is what makes a
     * doubled tick check a target once — it is re-evaluated under the row lock, so the loser updates
     * nothing. {@code updatedAt} is set explicitly because {@code @PreUpdate} does not fire for a bulk update.
     */
    private static final String CLAIM_ATTEMPT_QUERY = """
            UPDATE PostPublishTarget t
               SET t.attempts = t.attempts + 1,
                   t.updatedAt = :now
             WHERE t.id = :id
               AND t.state = com.conductor.entity.PostPublishTargetState.HANDED_OFF
               AND t.lane = com.conductor.entity.PublishLane.NATIVE
               AND t.attempts = :attempts
            """;

    /** Bounds one tick; anything not reached is picked up a minute later. Package-private for tests. */
    int batchSize = 50;

    /**
     * How many times a handed-off post is asked about before the row is resolved into {@code FAILED}.
     * Twenty checks at a minute apart is twenty minutes past the fire time — far longer than a platform
     * takes to publish something it already holds, and short enough that a human finds out the same
     * morning. Package-private (not final) so tests can shrink it.
     */
    int maxConfirmationAttempts = 20;

    private final PublishPlatformRegistry platformRegistry;
    private final PostPublishTargetRepository targetRepository;
    private final ActiveConnectionResolver connectionResolver;
    private final ActionInvocationService actionInvocationService;
    private final PublishOutcomeService publishOutcomeService;
    private final boolean enabled;

    /**
     * Transaction-bound shared {@code EntityManager}, used for the due finder and the conditional attempt
     * claim. Field-injected because that is the supported form of {@code @PersistenceContext};
     * package-private so unit tests can supply a stub.
     */
    @PersistenceContext
    EntityManager entityManager;

    /** Self-reference so the {@code REQUIRES_NEW} claim runs through the Spring proxy. */
    @Autowired
    @Lazy
    NativePublishConfirmationPoller self;

    public NativePublishConfirmationPoller(PublishPlatformRegistry platformRegistry,
                                           PostPublishTargetRepository targetRepository,
                                           ActiveConnectionResolver connectionResolver,
                                           ActionInvocationService actionInvocationService,
                                           PublishOutcomeService publishOutcomeService,
                                           @Value("${conductor.native-publish-confirmation.enabled:true}")
                                           boolean enabled) {
        this.platformRegistry = platformRegistry;
        this.targetRepository = targetRepository;
        this.connectionResolver = connectionResolver;
        this.actionInvocationService = actionInvocationService;
        this.publishOutcomeService = publishOutcomeService;
        this.enabled = enabled;
    }

    /**
     * A minute's granularity. The platform fires on its own schedule and Conductor is only observing, so
     * this is about how quickly a permalink shows up on the Post, not about the publishing SLO — the
     * app-managed poller's 30 seconds owns that.
     */
    @Scheduled(fixedDelay = 60_000)
    public void poll() {
        if (!enabled) {
            return;
        }
        runTick(OffsetDateTime.now());
    }

    /** One confirmation pass. Package-private so tests drive it instead of flipping the config guard on. */
    void runTick(OffsetDateTime now) {
        for (String targetId : self.dueTargetIds(now)) {
            try {
                confirmTarget(targetId, now);
            } catch (Exception e) {
                log.error("Native publish confirmation failed for target {}: {}", targetId, e.getMessage(), e);
            }
        }
    }

    /**
     * The handed-off rows whose fire time has passed, oldest first. Transactional (and reached through the
     * proxy) because it is issued straight through the shared {@code EntityManager} rather than through a
     * repository method that would carry its own.
     */
    @Transactional(readOnly = true)
    public List<String> dueTargetIds(OffsetDateTime now) {
        return entityManager.createQuery(DUE_QUERY, String.class)
                .setParameter("now", now)
                .setMaxResults(batchSize)
                .getResultList();
    }

    /** Everything one check needs, read while the claim transaction was still open. */
    record ConfirmationAttempt(String targetId, String postId, String platform, Connection connection,
                               PublishPlatform.ConfirmAction action, String platformPostId, String idempotencyKey,
                               int attempt) {}

    /** How one confirmation attempt ended, for the caller deciding whether to come back. */
    public enum ConfirmOutcome {
        /** The row reached a terminal state: confirmed live, or failed after the last attempt. */
        SETTLED,
        /** The platform does not report it live yet and attempts remain. */
        RETRY_LATER,
        /** Nothing was attempted: the row is not confirmable (wrong state, too early, no connection...). */
        SKIPPED
    }

    /**
     * Runs one confirmation attempt now: the request-time entry point for a CONFIRM {@code PublishTask}
     * arriving from Cloud Tasks (see {@code PublishTaskHandler}). Same claim as the sweep — the attempt
     * counter is bumped with a conditional UPDATE, so a duplicate delivery attempts nothing.
     */
    public ConfirmOutcome confirmNow(String targetId, OffsetDateTime now) {
        return confirmTarget(targetId, now);
    }

    private ConfirmOutcome confirmTarget(String targetId, OffsetDateTime now) {
        ConfirmationAttempt attempt = self.claimAttemptInNewTx(targetId, now);
        if (attempt == null) {
            return ConfirmOutcome.SKIPPED;
        }
        log.debug("Asking {} whether post {} for target {} is live (attempt {} of {})",
                attempt.platform(), attempt.platformPostId(), targetId, attempt.attempt(),
                maxConfirmationAttempts);

        ActionResult result = actionInvocationService.invoke(attempt.connection(),
                attempt.action().actionId(), Map.of(attempt.action().postIdParam(), attempt.platformPostId()),
                attempt.idempotencyKey(), List.of());

        Map<String, Object> output = result == null || result.output() == null ? Map.of() : result.output();
        boolean readable = result != null && result.success();
        if (readable && Boolean.TRUE.equals(attempt.action().liveness().apply(output))) {
            String platformPostId = firstNonBlank(stringValue(output, attempt.action().postIdOutputKey()),
                    attempt.platformPostId());
            publishOutcomeService.recordSuccess(attempt.targetId(), platformPostId,
                    stringValue(output, PERMALINK_OUTPUT_KEY));
            return ConfirmOutcome.SETTLED;
        }

        String reason = readable
                ? "the platform still does not report it as live"
                : "the check itself failed: " + (result == null ? "no result" : result.message());
        if (attempt.attempt() >= maxConfirmationAttempts) {
            publishOutcomeService.recordFailure(attempt.targetId(), exhaustedMessage(attempt, reason));
            return ConfirmOutcome.SETTLED;
        }
        log.debug("Target {} not confirmed live on attempt {} of {} ({}); leaving it handed off",
                attempt.targetId(), attempt.attempt(), maxConfirmationAttempts, reason);
        return ConfirmOutcome.RETRY_LATER;
    }

    /**
     * Takes one attempt on a handed-off target and returns what it takes to check it, or {@code null} when
     * this tick must not check it — the row is gone, is not this lane's, is no longer {@code HANDED_OFF},
     * its fire time has not passed, nothing was ever created on the platform, its platform has no
     * confirmation action, its connection cannot be resolved, or another tick took this attempt first.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmationAttempt claimAttemptInNewTx(String targetId, OffsetDateTime now) {
        PostPublishTarget target = targetRepository.findById(targetId).orElse(null);
        if (target == null) {
            log.debug("Post publish target {} vanished between the due query and its confirmation", targetId);
            return null;
        }
        if (target.getLane() != PublishLane.NATIVE) {
            log.debug("Post publish target {} is on the {} lane; not this poller's to confirm",
                    targetId, target.getLane());
            return null;
        }
        if (target.getState() != PostPublishTargetState.HANDED_OFF) {
            log.debug("Post publish target {} is {}, not HANDED_OFF; nothing to confirm",
                    targetId, target.getState());
            return null;
        }
        if (target.getFireTime() == null || target.getFireTime().isAfter(now)) {
            log.debug("Post publish target {} fires at {}; too early to ask whether it is live",
                    targetId, target.getFireTime());
            return null;
        }

        String platformPostId = target.getPlatformPostId();
        if (platformPostId == null || platformPostId.isBlank()) {
            log.warn("Post publish target {} is handed off but carries no platform post id; "
                    + "there is nothing to ask {} about", targetId, target.getPlatform());
            return null;
        }

        String platform = normalizedPlatform(target.getPlatform());
        PublishPlatform.ConfirmAction action = platformRegistry.find(platform)
                .map(PublishPlatform::confirm).orElse(null);
        if (action == null) {
            log.warn("Post publish target {} names platform '{}', which has no confirmation action; skipping",
                    targetId, target.getPlatform());
            return null;
        }

        WorkItem post = target.getWorkItem();
        String projectId = post == null || post.getProject() == null ? null : post.getProject().getId();
        Optional<Connection> connection = connectionResolver.resolveById(projectId, target.getConnectionId());
        if (connection.isEmpty()) {
            log.warn("Post publish target {} names connection {}, which is not available; "
                    + "cannot ask whether its post is live", targetId, target.getConnectionId());
            return null;
        }

        int attempt = target.getAttempts() + 1;
        int claimed = entityManager.createQuery(CLAIM_ATTEMPT_QUERY)
                .setParameter("id", targetId)
                .setParameter("attempts", target.getAttempts())
                .setParameter("now", OffsetDateTime.now())
                .executeUpdate();
        if (claimed == 0) {
            log.debug("Post publish target {} was checked by another tick; not checking it again", targetId);
            return null;
        }
        // The bulk update bypassed the persistence context — drop the stale copy so it cannot flush the
        // old attempt count back over the claim.
        entityManager.detach(target);

        return new ConfirmationAttempt(targetId, post == null ? null : post.getId(), platform,
                connection.get(), action, platformPostId,
                confirmationKey(target.getIdempotencyKey(), attempt), attempt);
    }

    /**
     * What a human reads when Conductor gave up waiting. Names the platform, the post it is looking for and
     * what the last check saw, because the fix is always "go look on the platform" and this is the only
     * place that says where.
     */
    private String exhaustedMessage(ConfirmationAttempt attempt, String reason) {
        return "Handed off to " + attempt.platform() + " as post " + attempt.platformPostId()
                + ", but it was never confirmed live after " + maxConfirmationAttempts
                + " checks — " + reason + ". Check the post on " + attempt.platform()
                + " and reschedule it if it did not go out.";
    }

    /**
     * One key per attempt. {@link ActionInvocationService} is claim-or-return on the key, so a shared key
     * would replay the first check's answer forever rather than asking the platform again.
     */
    private String confirmationKey(String targetIdempotencyKey, int attempt) {
        return "confirm:" + targetIdempotencyKey + ":" + attempt;
    }

    private static String stringValue(Map<String, Object> output, String key) {
        Object value = output.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static String normalizedPlatform(String platform) {
        return platform == null ? null : platform.trim().toLowerCase(Locale.ROOT);
    }
}
