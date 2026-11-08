package com.conductor.service.publish.tasks;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.integration.connector.tiktok.TikTokPermalinkFollowUp;
import com.conductor.integration.connector.tiktok.TikTokPhotoVerdictCheck;
import com.conductor.integration.connector.tiktok.TikTokClient;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.PublishOutcomeService;
import com.conductor.service.publish.PermalinkFollowUp;
import com.conductor.service.NativeHandoffService;
import com.conductor.service.NativePublishConfirmationPoller;
import com.conductor.service.publish.PostFormat;
import com.conductor.service.publish.PublishPlatform;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.workflow.PostPublishScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * What happens when a {@link PublishTask} arrives: the request-time entry point behind
 * {@code /internal/v1/publish-targets/{id}/*} (and the local timer). Routes each kind to the poller
 * that already knows how to do the work, and owns the three checks that make a task safe to deliver
 * more than once, late, early, or after the Post changed underneath it:
 *
 * <ol>
 *   <li><b>Stale</b>: the row's fire time no longer matches the snapshot the task carries — the Post was
 *       rescheduled and a fresh task exists. Dropped.</li>
 *   <li><b>Early</b>: the row's fire time is still ahead. Either the task was created at Cloud Tasks'
 *       30-day cap ({@link CloudTasksPublishTaskScheduler#MAX_SCHEDULE_AHEAD}) or a platform's hand-off
 *       window has not opened. Re-armed for the right moment, not acted on.</li>
 *   <li><b>Behind the row</b>: a CONFIRM whose attempt number is below the row's — the in-process sweep
 *       checked the row since this task was armed. Adopted, not dropped: the chain continues from the
 *       row's own attempt count, because on Cloud Run that sweep only runs while a request is being
 *       served and a row left to it alone is stranded once the instance idles. One ahead of the row is
 *       impossible in a healthy chain and is dropped.</li>
 * </ol>
 *
 * <p>Everything past those checks is a conditional claim in the poller it delegates to, so a task and
 * the sweep racing for the same row cannot both act. Honours the same {@code conductor.*.enabled} flags
 * as the pollers: an operator who switched a lane off gets no publishing from this path either.
 */
@Service
public class PublishTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(PublishTaskHandler.class);

    /** Slack for Cloud Tasks delivering a hair before the second the fire time names. */
    static final Duration EARLY_GRACE = Duration.ofSeconds(5);
    /** Spacing between confirmation attempts — the cadence the confirmation poller's sweep has. */
    static final Duration CONFIRM_INTERVAL = Duration.ofSeconds(60);

    private final PostPublishTargetRepository targetRepository;
    private final PublishPlatformRegistry platformRegistry;
    private final PostPublishScheduler postPublishScheduler;
    private final NativeHandoffService nativeHandoffService;
    private final NativePublishConfirmationPoller confirmationPoller;
    private final PublishTaskScheduler scheduler;
    private final PublishOutcomeService outcomeService;
    private final TikTokPermalinkFollowUp tikTokPermalinkFollowUp;
    private final TikTokPhotoVerdictCheck tikTokPhotoVerdictCheck;
    private final boolean dispatchEnabled;
    private final boolean handoffEnabled;
    private final boolean confirmEnabled;

    public PublishTaskHandler(PostPublishTargetRepository targetRepository,
                              PublishPlatformRegistry platformRegistry,
                              PostPublishScheduler postPublishScheduler,
                              NativeHandoffService nativeHandoffService,
                              NativePublishConfirmationPoller confirmationPoller,
                              PublishTaskScheduler scheduler,
                              PublishOutcomeService outcomeService,
                              TikTokPermalinkFollowUp tikTokPermalinkFollowUp,
                              TikTokPhotoVerdictCheck tikTokPhotoVerdictCheck,
                              @Value("${conductor.post-publish.enabled:true}") boolean dispatchEnabled,
                              @Value("${conductor.native-handoff.enabled:true}") boolean handoffEnabled,
                              @Value("${conductor.native-publish-confirmation.enabled:true}")
                              boolean confirmEnabled) {
        this.targetRepository = targetRepository;
        this.platformRegistry = platformRegistry;
        this.postPublishScheduler = postPublishScheduler;
        this.nativeHandoffService = nativeHandoffService;
        this.confirmationPoller = confirmationPoller;
        this.scheduler = scheduler;
        this.outcomeService = outcomeService;
        this.tikTokPermalinkFollowUp = tikTokPermalinkFollowUp;
        this.tikTokPhotoVerdictCheck = tikTokPhotoVerdictCheck;
        this.dispatchEnabled = dispatchEnabled;
        this.handoffEnabled = handoffEnabled;
        this.confirmEnabled = confirmEnabled;
    }

    public void handle(PublishTask task) {
        handle(task, OffsetDateTime.now());
    }

    void handle(PublishTask task, OffsetDateTime now) {
        PostPublishTarget target = targetRepository.findById(task.targetId()).orElse(null);
        if (target == null) {
            log.debug("{} arrived for publish target {}, which no longer exists", task.kind(), task.targetId());
            return;
        }
        // A PERMALINK follow-up is guarded by the row's state and its stored publish handle instead: it
        // runs after the row has published, and a reschedule cannot make its answer wrong.
        // A TIKTOK_VERDICT runs on a FAILED row and does its own staleness check (a retry restamps the fire time).
        if (task.kind() != PublishTaskKind.PERMALINK && task.kind() != PublishTaskKind.TIKTOK_VERDICT
                && !task.matchesFireTime(target.getFireTime())) {
            log.info("{} for publish target {} is stale (armed for {}, row now fires at {}); dropping",
                    task.kind(), task.targetId(), task.fireTime(), target.getFireTime());
            return;
        }
        switch (task.kind()) {
            case DISPATCH -> dispatch(task, target, now);
            case HANDOFF -> handoff(task, target, now);
            case CONFIRM -> confirm(task, target, now);
            case PERMALINK -> permalink(task, target, now);
            case TIKTOK_VERDICT -> tiktokVerdict(task, target, now);
        }
    }

    private void dispatch(PublishTask task, PostPublishTarget target, OffsetDateTime now) {
        if (!dispatchEnabled) {
            log.info("DISPATCH for publish target {} ignored: conductor.post-publish.enabled is false", target.getId());
            return;
        }
        if (target.getState() != PostPublishTargetState.PENDING) {
            log.debug("DISPATCH for publish target {} has nothing to do: row is {}", target.getId(), target.getState());
            return;
        }
        if (arrivedEarly(target.getFireTime(), now)) {
            rearm(PublishTask.dispatch(target.getId(), target.getFireTime(), target.getFireTime()));
            return;
        }
        postPublishScheduler.fireTarget(target.getId(), now);
    }

    private void handoff(PublishTask task, PostPublishTarget target, OffsetDateTime now) {
        if (!handoffEnabled) {
            log.info("HANDOFF for publish target {} ignored: conductor.native-handoff.enabled is false", target.getId());
            return;
        }
        if (target.getLane() != PublishLane.NATIVE) {
            log.debug("HANDOFF for publish target {} has nothing to do: row is on the {} lane",
                    target.getId(), target.getLane());
            return;
        }
        if (target.getState() == PostPublishTargetState.HANDED_OFF) {
            // Already handed off — by the immediate hand-off on scheduled entry, whose own transaction had
            // committed before the armer looked, or by the sweep. Whoever did it, the row needs a
            // confirmation chain; if one exists its attempt number will not match, and this one ends there.
            rearm(PublishTask.confirm(target.getId(), target.getFireTime(),
                    later(now, target.getFireTime()), target.getAttempts()));
            return;
        }
        if (target.getState() != PostPublishTargetState.PENDING) {
            log.debug("HANDOFF for publish target {} has nothing to do: row is {}", target.getId(), target.getState());
            return;
        }
        PublishPlatform.HandoffWindow window = platformRegistry.find(target.getPlatform())
                .map(platform -> platform.windowFor(PostFormat.parse(target.getFormat()))).orElse(null);
        if (window != null && window.tooFarOut(now, target.getFireTime())) {
            rearm(PublishTask.handoff(target.getId(), target.getFireTime(),
                    target.getFireTime().minus(window.maxLead())));
            return;
        }
        nativeHandoffService.handoffNow(target.getId(), now);
        // A successful hand-off leaves the row HANDED_OFF; the platform now owns the clock and the next
        // thing to do is ask, at the fire time, whether it went live.
        targetRepository.findById(target.getId())
                .filter(row -> row.getState() == PostPublishTargetState.HANDED_OFF)
                .ifPresent(row -> rearm(PublishTask.confirm(row.getId(), row.getFireTime(),
                        later(now, row.getFireTime()), row.getAttempts())));
    }

    private void confirm(PublishTask task, PostPublishTarget target, OffsetDateTime now) {
        if (!confirmEnabled) {
            log.info("CONFIRM for publish target {} ignored: conductor.native-publish-confirmation.enabled is false",
                    target.getId());
            return;
        }
        if (target.getState() != PostPublishTargetState.HANDED_OFF) {
            log.debug("CONFIRM for publish target {} has nothing to do: row is {}", target.getId(), target.getState());
            return;
        }
        if (arrivedEarly(target.getFireTime(), now)) {
            rearm(PublishTask.confirm(target.getId(), target.getFireTime(), target.getFireTime(), task.attempt()));
            return;
        }
        if (target.getAttempts() > task.attempt()) {
            // The sweep got here first. Continue from where the row actually is rather than ending the only
            // timed chain this row has. The re-arm time derives from the row's last check, not from this
            // request's clock, so two stale deliveries adopting at once name the same Cloud Task and the
            // queue keeps one.
            OffsetDateTime lastChecked = target.getUpdatedAt() == null ? now : target.getUpdatedAt();
            log.debug("CONFIRM for publish target {} carries attempt {} but the row is on {}; adopting the chain",
                    target.getId(), task.attempt(), target.getAttempts());
            rearm(PublishTask.confirm(target.getId(), target.getFireTime(),
                    later(now, lastChecked.plus(CONFIRM_INTERVAL)), target.getAttempts()));
            return;
        }
        if (target.getAttempts() < task.attempt()) {
            log.warn("CONFIRM for publish target {} carries attempt {} but the row is only on {}; dropped",
                    target.getId(), task.attempt(), target.getAttempts());
            return;
        }
        NativePublishConfirmationPoller.ConfirmOutcome outcome = confirmationPoller.confirmNow(target.getId(), now);
        if (outcome == NativePublishConfirmationPoller.ConfirmOutcome.RETRY_LATER) {
            rearm(PublishTask.confirm(target.getId(), target.getFireTime(), now.plus(CONFIRM_INTERVAL),
                    task.attempt() + 1));
        }
    }

    /**
     * Asks TikTok again for the link of a target that published without one. On an id the link goes
     * through {@link PublishOutcomeService#recordSuccess}; otherwise the task is re-armed on the backoff,
     * and past the last step the chain ends with a log line - the post may simply not be public.
     */
    private void permalink(PublishTask task, PostPublishTarget target, OffsetDateTime now) {
        if (!dispatchEnabled) {
            log.info("PERMALINK for publish target {} ignored: conductor.post-publish.enabled is false", target.getId());
            return;
        }
        boolean hasLink = target.getPermalink() != null && !target.getPermalink().isBlank();
        if (target.getState() != PostPublishTargetState.PUBLISHED || hasLink) {
            log.debug("PERMALINK for publish target {} has nothing to do: row is {}{}", target.getId(),
                    target.getState(), hasLink ? " and already has a link" : "");
            return;
        }
        PermalinkFollowUp followUp = PermalinkFollowUp.parse(target.getResumeCheckpoint()).orElse(null);
        if (followUp == null) {
            log.debug("PERMALINK for publish target {} has nothing to do: no follow-up on the row", target.getId());
            return;
        }
        Optional<TikTokPermalinkFollowUp.Found> found = Optional.empty();
        try {
            found = tikTokPermalinkFollowUp.check(target, followUp);
        } catch (Exception e) {
            log.warn("PERMALINK check {} for publish target {} failed: {}", task.attempt(), target.getId(),
                    e.getMessage());
        }
        if (found.isPresent()) {
            outcomeService.recordSuccess(target.getId(), found.get().postId(), found.get().permalink());
            log.info("Target {} got its link after {} follow-up(s): {}", target.getId(), task.attempt() + 1,
                    found.get().permalink());
            return;
        }
        int next = task.attempt() + 1;
        if (next >= PublishTaskArmer.PERMALINK_BACKOFF.size()) {
            log.info("Giving up on a link for publish target {} (publish {}) after {} checks; the post may not "
                    + "be public", target.getId(), followUp.publishId(), next);
            return;
        }
        rearm(PublishTask.permalink(target.getId(), task.fireTime(),
                now.plus(PublishTaskArmer.PERMALINK_BACKOFF.get(next)), next));
    }

    /**
     * Asks TikTok what became of a photo post Conductor gave up on. The row must still be FAILED under the
     * fire time the chain was armed for (a manual retry restamps it and moves the row on, which ends the
     * chain), and its checkpoint must still hold live photo links.
     *
     * <ul>
     *   <li>PUBLISH_COMPLETE: the late publish is recorded; a missing link starts the PERMALINK chain.</li>
     *   <li>FAILED: TikTok's reason replaces the message and the links are released so a retry is allowed.</li>
     *   <li>anything else: re-armed on the cadence; after the last check the message says a retry is safe.</li>
     * </ul>
     */
    private void tiktokVerdict(PublishTask task, PostPublishTarget target, OffsetDateTime now) {
        if (!dispatchEnabled) {
            log.info("TIKTOK_VERDICT for publish target {} ignored: conductor.post-publish.enabled is false",
                    target.getId());
            return;
        }
        if (target.getFireTime() != null && !task.matchesFireTime(target.getFireTime())) {
            log.info("TIKTOK_VERDICT for publish target {} is stale (armed for {}, row now fires at {}); dropping",
                    target.getId(), task.fireTime(), target.getFireTime());
            return;
        }
        if (target.getState() != PostPublishTargetState.FAILED) {
            log.debug("TIKTOK_VERDICT for publish target {} has nothing to do: row is {}", target.getId(),
                    target.getState());
            return;
        }
        TikTokPhotoVerdictCheck.Pending pending = tikTokPhotoVerdictCheck.pending(target).orElse(null);
        if (pending == null) {
            log.info("TikTok verdict chain for target {} stopped: no live photo links on its checkpoint",
                    target.getId());
            return;
        }
        TikTokPhotoVerdictCheck.Reading reading = null;
        try {
            reading = tikTokPhotoVerdictCheck.read(target, pending.publishId());
        } catch (Exception e) {
            log.warn("TikTok verdict check {} for publish_id={} (target {}) failed: {}", task.attempt(),
                    pending.publishId(), target.getId(), e.getMessage());
        }
        if (reading != null) {
            TikTokClient.PublishStatus status = reading.status();
            log.info("TikTok verdict check {} publish_id={} target={} status={} failReason={} downloadedBytes={} "
                            + "logId={} minutesSinceInit={} minutesUntilLinksExpire={}", task.attempt(),
                    pending.publishId(), target.getId(), status.status(), status.failReason(),
                    status.downloadedBytes(), status.logId(),
                    pending.initAt() == null ? "unknown" : Duration.between(pending.initAt(), now.toInstant()).toMinutes(),
                    Duration.between(now.toInstant(), pending.linksExpireAt()).toMinutes());
            if (status.complete()) {
                verdictPublished(target, pending, reading, now);
                return;
            }
            if (status.failed()) {
                String reason = status.failReason() == null || status.failReason().isBlank()
                        ? "no reason given" : status.failReason().replace('_', ' ');
                outcomeService.reviseFailureMessage(target.getId(),
                        "TikTok did not publish the photo post: " + reason + ". It is safe to retry.");
                tikTokPhotoVerdictCheck.releaseLinks(target);
                log.info("TikTok verdict chain for target {} stopped: TikTok failed publish_id={} ({})",
                        target.getId(), pending.publishId(), status.failReason());
                return;
            }
        }
        int next = task.attempt() + 1;
        Optional<OffsetDateTime> nextAt = PublishTaskArmer.nextVerdictAt(next, now, pending.linksExpireAt());
        if (nextAt.isPresent()) {
            rearm(PublishTask.tiktokVerdict(target.getId(), task.fireTime(), nextAt.get(), next));
            return;
        }
        outcomeService.reviseFailureMessage(target.getId(), "TikTok never downloaded the images before the image "
                + "links expired (" + formatUtc(pending.linksExpireAt()) + "), so the photo post was not "
                + "published. It is safe to retry now.");
        log.info("TikTok verdict chain for target {} stopped: publish_id={} still unfinished after the last check",
                target.getId(), pending.publishId());
    }

    private void verdictPublished(PostPublishTarget target, TikTokPhotoVerdictCheck.Pending pending,
                                  TikTokPhotoVerdictCheck.Reading reading, OffsetDateTime now) {
        boolean moved = outcomeService.recordLatePublish(target.getId(), pending.publishId(), reading.postId(),
                reading.permalink(), "photo");
        String late = pending.initAt() == null ? "late" : formatLateness(Duration.between(pending.initAt(), now.toInstant())) + " after init";
        if (moved) {
            log.warn("TikTok published photo post {} {} (target {}); the destination is now Published",
                    pending.publishId(), late, target.getId());
        } else {
            log.info("TikTok published photo post {} {} but target {} is no longer FAILED; nothing recorded",
                    pending.publishId(), late, target.getId());
        }
    }

    private static String formatLateness(Duration d) {
        return d.toHours() + "h" + String.format("%02d", d.toMinutesPart()) + "m";
    }

    private static String formatUtc(java.time.Instant instant) {
        return java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.ROOT)
                .withZone(java.time.ZoneOffset.UTC).format(instant) + " UTC";
    }

    private static boolean arrivedEarly(OffsetDateTime fireTime, OffsetDateTime now) {
        return fireTime.isAfter(now.plus(EARLY_GRACE));
    }

    private void rearm(PublishTask task) {
        log.debug("Re-arming {} for publish target {} at {}", task.kind(), task.targetId(), task.notBefore());
        scheduler.scheduleAfterCommit(task);
    }

    private static OffsetDateTime later(OffsetDateTime a, OffsetDateTime b) {
        return b.isAfter(a) ? b : a;
    }
}
