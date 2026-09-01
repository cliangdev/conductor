package com.conductor.entity;

/**
 * Lifecycle of one {@link PostPublishTarget}.
 *
 * <p>{@link #PENDING} is the only state any poller picks up. A {@link PublishLane#NATIVE} target
 * moves PENDING → {@link #HANDED_OFF} (the platform's scheduler owns it from there) and on to
 * {@link #PUBLISHED} once the platform confirms it went live. An {@link PublishLane#APP_MANAGED}
 * target moves PENDING → {@link #PUBLISHING} → {@link #PUBLISHED}. A {@link PublishLane#MANUAL} target
 * moves PENDING → {@link #AWAITING_MANUAL} → {@link #PUBLISHED}, the last step driven by a human rather
 * than a platform. {@link #REVOKED} is the terminal state after an already-delivered post has been taken
 * back down.
 */
public enum PostPublishTargetState {
    PENDING,
    HANDED_OFF,
    PUBLISHING,

    /**
     * A {@link PublishLane#MANUAL} target whose fire time has come: Conductor has done everything it is
     * going to do, and the post is now waiting on a person to publish it by hand and record the live URL.
     *
     * <p>Counted as in-flight by the Post-level roll-up, deliberately. The alternative reading — that a
     * target nothing is actively working on has finished — would roll a Post up to its failed status the
     * moment a manual target came due, marking as failed a post whose human simply has not got to it yet.
     * It leaves this state only when a human completes it or the Post is unscheduled, so unlike
     * {@link #PUBLISHING} it can wait here indefinitely and is exempt from the stranded-row sweep.
     */
    AWAITING_MANUAL,
    PUBLISHED,
    FAILED,
    REVOKED
}
