package com.conductor.entity;

/**
 * How a {@link PostPublishTarget} reaches its platform.
 *
 * <p>{@link #NATIVE} hands the post (and its scheduled time) to the platform's own scheduler, which
 * then owns publishing it — Conductor's job ends at the hand-off. {@link #APP_MANAGED} keeps the post
 * here until {@code fireTime} comes due, and Conductor publishes it itself.
 */
public enum PublishLane {
    NATIVE,
    APP_MANAGED
}
