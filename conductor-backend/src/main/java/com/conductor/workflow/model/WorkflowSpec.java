package com.conductor.workflow.model;

import java.util.Map;

/**
 * The typed, immutable form of an automation workflow's YAML — the single parsed representation
 * shared by validation and execution, replacing the ad hoc {@code new Yaml().load()} + Map-walking
 * that used to be duplicated across {@code WorkflowValidator}, {@code WorkflowJobOrchestrator},
 * {@code WorkflowTriggerService}, {@code WorkflowController}, {@code JobDispatchPayloadService},
 * and {@code UpstreamOutputsResolver}. Build one via {@link WorkflowYamlParser#parse(String)}.
 *
 * <p>{@code jobs} preserves YAML declaration order (insertion-ordered map) since job iteration
 * order is user-visible (e.g. run-detail job listing) and was order-preserving under the old
 * SnakeYAML-Map-returns-LinkedHashMap behavior.
 *
 * @param name        workflow display name, or null (the API request carries this separately)
 * @param triggers    the parsed {@code on:} block
 * @param concurrency the {@code concurrency:} value (e.g. {@code "single"}), or null
 * @param jobs        job id -> {@link JobSpec}, in declaration order
 * @param raw         the full parsed document, verbatim — used by {@code WorkflowValidator} for the
 *                    handful of structural gates (missing {@code on:}/{@code jobs:}, wrong shape)
 *                    that must reject before any typed field can be trusted
 */
public record WorkflowSpec(String name, TriggersSpec triggers, String concurrency,
                           Map<String, JobSpec> jobs, Map<String, Object> raw) {

    public WorkflowSpec {
        jobs = Copies.map(jobs);
        raw = Copies.map(raw);
    }
}
