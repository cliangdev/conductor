package com.conductor.entity;

/**
 * Lifecycle of one {@link PostPublishTarget}.
 *
 * <p>{@link #PENDING} is the only state either poller picks up. A {@link PublishLane#NATIVE} target
 * moves PENDING → {@link #HANDED_OFF} (the platform's scheduler owns it from there) and on to
 * {@link #PUBLISHED} once the platform confirms it went live. An {@link PublishLane#APP_MANAGED}
 * target moves PENDING → {@link #PUBLISHING} → {@link #PUBLISHED}. {@link #REVOKED} is the terminal
 * state after an already-delivered post has been taken back down.
 */
public enum PostPublishTargetState {
    PENDING,
    HANDED_OFF,
    PUBLISHING,
    PUBLISHED,
    FAILED,
    REVOKED
}
