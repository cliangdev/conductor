package com.conductor.entity;

/**
 * How a {@link PostPublishTarget} reaches its platform.
 *
 * <p>{@link #NATIVE} hands the post (and its scheduled time) to the platform's own scheduler, which
 * then owns publishing it — Conductor's job ends at the hand-off. {@link #APP_MANAGED} keeps the post
 * here until {@code fireTime} comes due, and Conductor publishes it itself. {@link #MANUAL} calls no
 * platform at all: a human posts it by hand and records the result.
 */
public enum PublishLane {
    NATIVE,
    APP_MANAGED,

    /**
     * Nobody automates this one. At {@code fireTime} the target moves to
     * {@link PostPublishTargetState#AWAITING_MANUAL} and waits for a human to post it themselves and paste
     * back the live URL; no credential is used and no API is called, which is why a MANUAL target carries a
     * null {@code connectionId} and is the only lane that can be selected for a platform the project has
     * never connected.
     *
     * <p>It exists because the alternative was a dead end: a publishing Workflow refuses its approval gate
     * without at least one target, so before this lane a project with no social integration — one still
     * waiting on platform App Review, or one that simply posts by hand — could not move a Post past In
     * Review at all. The whole pipeline is worth having without an API behind it: the review gate, the
     * media rules, the schedule and the calendar all still apply, and the only thing a human takes over is
     * the posting itself.
     *
     * <p>Neither poller can touch it. {@code PostPublishScheduler} and {@code NativeHandoffService} both
     * re-assert their own lane in the WHERE clause of the UPDATE that claims a row, so a MANUAL target is
     * not merely skipped by them — it is unclaimable, and no code path exists that could publish it
     * automatically.
     */
    MANUAL
}
