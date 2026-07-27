package com.conductor.signal;

/**
 * Global ordering for {@link SignalSubscriber#order()}. There is a single shared order (not a
 * per-signal-type order) because the whole point is to reproduce today's
 * {@code NotificationDispatcher.dispatch} fan-out order on both paths it currently serves -- the
 * Work Item status-changed path ({@code sendNotification} -> workflow automation -> lifecycle ->
 * knowledge) and the GitHub merged-PR path ({@code sendNotification} -> workflow automation ->
 * ... -> the merge-specific consumer that today lives inline in {@code GitHubConnector}). Once
 * both paths are migrated onto real subscribers, one order list has to serve both without
 * reshuffling either.
 *
 * <p>Values are spaced by 10 to leave room for future subscribers to interleave without a
 * renumbering migration.
 */
public final class SignalDispatchOrder {

    /** The former unguarded {@code sendNotification} call -- first, and PROPAGATE by default today. */
    public static final int NOTIFICATION = 10;

    /** {@code WorkflowTriggerService.onConductorEvent} / {@code onGitHubPullRequest}. */
    public static final int WORKFLOW_AUTOMATION = 20;

    /** {@code LifecycleTriggerDispatcher.onConductorEvent}. */
    public static final int LIFECYCLE = 30;

    /** {@code KnowledgeEventTap.onConductorEvent}. */
    public static final int KNOWLEDGE = 40;

    /** The merge-specific consumer on the {@code github.pull_request_merged} path. */
    public static final int PULL_REQUEST_MERGE = 50;

    /** A future disposition-policy subscriber; last, since it acts on the outcome of everything above. */
    public static final int DISPOSITION_POLICY = 60;

    private SignalDispatchOrder() {
    }
}
