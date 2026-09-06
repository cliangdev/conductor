package com.conductor.service.publish.tasks;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One timed unit of publishing work, addressed to a single {@code post_publish_target} row and carried by
 * a Cloud Task (or its local stand-in) so it arrives as a genuine inbound HTTP request at the moment it is
 * due — see {@link PublishTaskScheduler} for why that matters on Cloud Run.
 *
 * @param kind      what to do when it arrives
 * @param targetId  the row it is about
 * @param fireTime  the target's fire time when the task was armed, to the second. A task whose snapshot no
 *                  longer matches the row is stale — the Post was rescheduled and a fresh task exists — and
 *                  is dropped on arrival rather than acted on.
 * @param notBefore when the task should be delivered
 * @param attempt   for {@link PublishTaskKind#CONFIRM}, the {@code attempts} count the row is expected to
 *                  hold when the task arrives; a mismatch means another confirmation chain owns the row and
 *                  this one ends. Zero for the other kinds.
 */
public record PublishTask(PublishTaskKind kind, String targetId, OffsetDateTime fireTime,
                          OffsetDateTime notBefore, int attempt) {

    public PublishTask {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(fireTime, "fireTime");
        Objects.requireNonNull(notBefore, "notBefore");
    }

    public static PublishTask dispatch(String targetId, OffsetDateTime fireTime, OffsetDateTime notBefore) {
        return new PublishTask(PublishTaskKind.DISPATCH, targetId, fireTime, notBefore, 0);
    }

    public static PublishTask handoff(String targetId, OffsetDateTime fireTime, OffsetDateTime notBefore) {
        return new PublishTask(PublishTaskKind.HANDOFF, targetId, fireTime, notBefore, 0);
    }

    public static PublishTask confirm(String targetId, OffsetDateTime fireTime, OffsetDateTime notBefore,
                                      int attempt) {
        return new PublishTask(PublishTaskKind.CONFIRM, targetId, fireTime, notBefore, attempt);
    }

    /** The fire-time snapshot as it travels in the task URL: epoch seconds. */
    public long fireTimeEpochSecond() {
        return fireTime.toEpochSecond();
    }

    /** True when {@code rowFireTime} is the fire time this task was armed for, to the second. */
    public boolean matchesFireTime(OffsetDateTime rowFireTime) {
        return rowFireTime != null && rowFireTime.toEpochSecond() == fireTimeEpochSecond();
    }
}
