package com.conductor.workflow.model;

import java.util.List;
import java.util.Map;

/**
 * A single job within a {@link WorkflowSpec}. {@code needs} is always normalized to a list (the
 * YAML accepts either a single job id string or a list). {@code runsOn} is the {@code runs-on}
 * scalar as a String when the YAML value is a scalar; a list-valued {@code runs-on: [a, b]} (rare —
 * only {@code WorkflowValidator} inspects those, via {@link #raw()}) resolves to null here rather
 * than throwing, since nothing downstream has ever meaningfully executed a job with a list-valued
 * {@code runs-on}.
 *
 * @param id           job id (the key under {@code jobs:})
 * @param needs        upstream job ids this job waits on, normalized to a list (empty if none)
 * @param runsOn       the {@code runs-on} scalar, or null if absent/list-valued
 * @param ifCondition  the job's {@code if:} expression, or null
 * @param loop         the job's {@code loop:} block, or null if absent
 * @param steps        all steps in declaration order, including a trailing {@code condition} step
 * @param raw          the job's full source map, verbatim
 */
public record JobSpec(String id, List<String> needs, String runsOn, String ifCondition,
                       LoopSpec loop, List<StepSpec> steps, Map<String, Object> raw) {

    public JobSpec {
        needs = Copies.list(needs);
        steps = Copies.list(steps);
        raw = Copies.map(raw);
    }

    /** All steps except a trailing {@code condition} step — what the engine actually executes. */
    public List<StepSpec> executableSteps() {
        return steps.stream().filter(s -> !"condition".equals(s.type())).toList();
    }
}
