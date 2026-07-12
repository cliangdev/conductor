package com.conductor.workflow.model;

/**
 * The {@code on.schedule} trigger. Present (non-null on {@link TriggersSpec#schedule()}) only when
 * the YAML's {@code schedule:} block is itself a mapping — a list-valued {@code schedule:} (seen in
 * one docs example) is not actually supported by the engine today ({@code WorkflowTriggerService}
 * only ever read the Map form), so it's preserved as a no-op rather than newly rejected.
 *
 * @param cron the 5-field cron expression, or null if the block didn't declare one
 */
public record ScheduleTrigger(String cron) {
}
