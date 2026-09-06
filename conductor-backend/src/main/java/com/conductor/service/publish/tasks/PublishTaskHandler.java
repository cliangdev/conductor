package com.conductor.service.publish.tasks;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.NativeHandoffService;
import com.conductor.service.NativePublishConfirmationPoller;
import com.conductor.service.publish.PublishPlatform;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.workflow.PostPublishScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;

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
 *   <li><b>Owned elsewhere</b>: a CONFIRM whose attempt number is not the row's — another chain is
 *       polling this row. Dropped so the two never fork.</li>
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
    private final boolean dispatchEnabled;
    private final boolean handoffEnabled;
    private final boolean confirmEnabled;

    public PublishTaskHandler(PostPublishTargetRepository targetRepository,
                              PublishPlatformRegistry platformRegistry,
                              PostPublishScheduler postPublishScheduler,
                              NativeHandoffService nativeHandoffService,
                              NativePublishConfirmationPoller confirmationPoller,
                              PublishTaskScheduler scheduler,
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
        if (!task.matchesFireTime(target.getFireTime())) {
            log.info("{} for publish target {} is stale (armed for {}, row now fires at {}); dropping",
                    task.kind(), task.targetId(), task.fireTime(), target.getFireTime());
            return;
        }
        switch (task.kind()) {
            case DISPATCH -> dispatch(task, target, now);
            case HANDOFF -> handoff(task, target, now);
            case CONFIRM -> confirm(task, target, now);
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
                .map(PublishPlatform::window).orElse(null);
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
        if (target.getAttempts() != task.attempt()) {
            log.debug("CONFIRM for publish target {} carries attempt {} but the row is on {}; another chain owns it",
                    target.getId(), task.attempt(), target.getAttempts());
            return;
        }
        NativePublishConfirmationPoller.ConfirmOutcome outcome = confirmationPoller.confirmNow(target.getId(), now);
        if (outcome == NativePublishConfirmationPoller.ConfirmOutcome.RETRY_LATER) {
            rearm(PublishTask.confirm(target.getId(), target.getFireTime(), now.plus(CONFIRM_INTERVAL),
                    task.attempt() + 1));
        }
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
