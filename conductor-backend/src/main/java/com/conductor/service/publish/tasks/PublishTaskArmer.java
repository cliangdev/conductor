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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
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

    /**
     * Optional: null in a plain unit test. Used to re-read a row the caller's persistence context may hold
     * stale — see {@link #armPost}.
     */
    @PersistenceContext
    EntityManager entityManager;

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
        List<PostPublishTarget> targets = targetRepository.findAllByWorkItemId(post.getId());
        // Entering the scheduled status restamps every PENDING row's fire time in its own transaction
        // (PublishTargetService#restampFireTimes is REQUIRES_NEW) after the gate validators loaded those
        // same rows into the caller's context. A query here would hand back the cached, pre-restamp copies
        // and arm a task for the old time, which the handler would then rightly drop as stale — leaving the
        // Post to the sweep. Flush what the caller itself changed, then re-read the rows from the database.
        refresh(targets);
        for (PostPublishTarget target : targets) {
            Optional<PublishTask> task = taskFor(target, now);
            if (task.isPresent()) {
                scheduler.scheduleAfterCommit(task.get());
                armed++;
            }
        }
        log.debug("Armed {} publish task(s) for post {}", armed, post.getId());
        return armed;
    }

    private void refresh(List<PostPublishTarget> targets) {
        if (entityManager == null) {
            return;
        }
        boolean flushed = false;
        for (PostPublishTarget target : targets) {
            if (entityManager.contains(target)) {
                if (!flushed && entityManager.isJoinedToTransaction()) {
                    entityManager.flush();
                    flushed = true;
                }
                entityManager.refresh(target);
            }
        }
    }

    /**
     * Delays before each PERMALINK follow-up for a target that published without a link: about an hour in
     * all, after which the chain ends (a post that is not public may never get an id).
     */
    public static final List<Duration> PERMALINK_BACKOFF = List.of(Duration.ofMinutes(2), Duration.ofMinutes(5),
            Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofMinutes(15));

    /**
     * How long past the image links' expiry the late-verdict chain keeps asking: TikTok documents that after
     * it starts fetching it works for up to an hour, and a fetch can begin on the very last second a link
     * lives.
     */
    public static final Duration VERDICT_GRACE = Duration.ofMinutes(70);

    /** Delay before check number {@code step} (zero-based): 15 min, 30 min, then hourly. */
    static Duration verdictDelay(int step) {
        return step == 0 ? Duration.ofMinutes(15) : step == 1 ? Duration.ofMinutes(30) : Duration.ofHours(1);
    }

    /**
     * When check number {@code step} should run, given that it is {@code now}: the cadence's next slot,
     * pulled in to the last check at {@code linksExpireAt + 70 min}. Empty when that last check has already
     * come (within a minute of it), so the chain ends.
     */
    public static Optional<OffsetDateTime> nextVerdictAt(int step, OffsetDateTime now, java.time.Instant linksExpireAt) {
        OffsetDateTime deadline = OffsetDateTime.ofInstant(linksExpireAt.plus(VERDICT_GRACE), now.getOffset());
        if (!now.isBefore(deadline.minusMinutes(1))) {
            return Optional.empty();
        }
        OffsetDateTime at = now.plus(verdictDelay(step));
        return Optional.of(at.isAfter(deadline) ? deadline : at);
    }

    /**
     * Starts the late-verdict chain for a TikTok photo target that was just parked as HANDED_OFF while its
     * image links are still live. Safe inside a transaction.
     *
     * @return true when a first check was armed
     */
    public boolean armTikTokVerdict(PostPublishTarget target, java.time.Instant linksExpireAt) {
        OffsetDateTime now = OffsetDateTime.now();
        Optional<OffsetDateTime> first = nextVerdictAt(0, now, linksExpireAt);
        if (first.isEmpty()) {
            return false;
        }
        scheduler.scheduleAfterCommit(PublishTask.tiktokVerdict(target.getId(),
                target.getFireTime() == null ? now : target.getFireTime(), first.get(), 0));
        log.info("Armed the TikTok verdict chain for target {}: first check {}, last check {}", target.getId(),
                first.get(), linksExpireAt.plus(VERDICT_GRACE));
        return true;
    }

    /** Starts the PERMALINK chain for a target that published without a link. Safe inside a transaction. */
    public void armPermalinkFollowUp(PostPublishTarget target) {
        OffsetDateTime now = OffsetDateTime.now();
        scheduler.scheduleAfterCommit(PublishTask.permalink(target.getId(),
                target.getFireTime() == null ? now : target.getFireTime(), now.plus(PERMALINK_BACKOFF.get(0)), 0));
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
