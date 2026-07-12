package com.conductor.workflow;

/**
 * Upstream/step status facts a condition expression's {@code always()}/{@code success()}/
 * {@code failure()} functions read. Computed by {@link WorkflowJobOrchestrator} from a
 * {@link RuntimeContext}'s {@code jobResults} (job-level {@code if:}) — the same context is reused
 * for a step's {@code if:}, per the job's needs, not the step's own job-local prior-step results.
 *
 * <p>{@code anyUpstreamFailed} and {@code allUpstreamSucceeded} are deliberately independent facts,
 * not complements of each other: a SKIPPED need makes {@code allUpstreamSucceeded} false (so {@code
 * success()} is false — a skip cascades through the default condition, matching GitHub Actions) but
 * does NOT make {@code anyUpstreamFailed} true (so {@code failure()} stays false for a skip — only a
 * real FAILED/LOOP_EXHAUSTED need trips it).
 *
 * @param anyUpstreamFailed whether any of the job's {@code needs} ended FAILED or LOOP_EXHAUSTED
 * @param allUpstreamSucceeded whether every one of the job's {@code needs} ended SUCCESS (false if
 *                             any need was FAILED, LOOP_EXHAUSTED, or SKIPPED)
 */
public record ConditionStatusContext(boolean anyUpstreamFailed, boolean allUpstreamSucceeded) {

    /** No upstream-failure information available (e.g. a root job with no {@code needs}). */
    public static final ConditionStatusContext NONE = new ConditionStatusContext(false, true);
}
