package com.conductor.service.publish.tasks;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.publish.PostFormat;
import com.conductor.service.publish.PublishPlatform;
import com.conductor.service.publish.PublishPlatformRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Turns a Post's targets into timed {@link PublishTask}s the moment they become schedulable — on entry
 * into the scheduled status and on a retry of failed targets — so each one arrives as a request at
 * exactly the time the pollers would otherwise have to notice it.
 *
 * <p>One task per target, by lane and state:
 * <ul>
 *   <li>{@code PENDING} on APP_MANAGED or MANUAL: {@link PublishTaskKind#DISPATCH} at the fire time.</li>
 *   <li>{@code PENDING} on NATIVE: {@link PublishTaskKind#HANDOFF} when the platform's hand-off window
 *       opens (the fire time less its maximum lead, or now if that is already past or it has none).</li>
 *   <li>{@code HANDED_OFF}: {@link PublishTaskKind#CONFIRM} at the fire time, carrying the row's current
 *       attempt count so a second arming cannot start a second confirmation chain.</li>
 * </ul>
 * Anything else — publishing, published, failed, revoked, or with no fire time yet — has nothing timed
 * ahead of it and gets no task.
 *
 * <p>Arming more than once is harmless: every task runs the same conditional claim the pollers do, so
 * duplicates update zero rows. Unscheduling never cancels a task; the row's state (and the fire-time
 * snapshot every task carries) makes a stale task a no-op on arrival.
 */
@Service
public class PublishTaskArmer {

    private static final Logger log = LoggerFactory.getLogger(PublishTaskArmer.class);

    private final PostPublishTargetRepository targetRepository;
    private final PublishPlatformRegistry platformRegistry;
    private final PublishTaskScheduler scheduler;

    public PublishTaskArmer(PostPublishTargetRepository targetRepository,
                            PublishPlatformRegistry platformRegistry,
                            PublishTaskScheduler scheduler) {
        this.targetRepository = targetRepository;
        this.platformRegistry = platformRegistry;
        this.scheduler = scheduler;
    }

    /** Arms every target of {@code post} that has timed work ahead of it. Safe inside a transaction. */
    public int armPost(WorkItem post) {
        if (post == null) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now();
        int armed = 0;
        for (PostPublishTarget target : targetRepository.findAllByWorkItemId(post.getId())) {
            Optional<PublishTask> task = taskFor(target, now);
            if (task.isPresent()) {
                scheduler.scheduleAfterCommit(task.get());
                armed++;
            }
        }
        log.debug("Armed {} publish task(s) for post {}", armed, post.getId());
        return armed;
    }

    /** The task {@code target} needs next, if any. */
    public Optional<PublishTask> taskFor(PostPublishTarget target, OffsetDateTime now) {
        OffsetDateTime fireTime = target.getFireTime();
        if (fireTime == null || target.getLane() == null || target.getState() == null) {
            return Optional.empty();
        }
        String id = target.getId();
        return switch (target.getState()) {
            case PENDING -> target.getLane() == PublishLane.NATIVE
                    ? Optional.of(PublishTask.handoff(id, fireTime, handoffOpensAt(target, now)))
                    : Optional.of(PublishTask.dispatch(id, fireTime, fireTime));
            case HANDED_OFF -> target.getLane() == PublishLane.NATIVE
                    ? Optional.of(PublishTask.confirm(id, fireTime, later(now, fireTime), target.getAttempts()))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** When the platform will first accept the hand-off: fire time less its maximum lead, never in the past. */
    OffsetDateTime handoffOpensAt(PostPublishTarget target, OffsetDateTime now) {
        Duration maxLead = platformRegistry.find(target.getPlatform())
                .map(platform -> platform.maxLeadFor(PostFormat.parse(target.getFormat())))
                .orElse(null);
        if (maxLead == null) {
            return now;
        }
        return later(now, target.getFireTime().minus(maxLead));
    }

    private static OffsetDateTime later(OffsetDateTime a, OffsetDateTime b) {
        return b.isAfter(a) ? b : a;
    }
}
