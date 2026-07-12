package com.conductor.workflow.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single step within a {@link JobSpec}. {@code type} is always the RESOLVED step type — the
 * single home of what used to be {@code WorkflowJobOrchestrator#resolveStepType} /
 * {@code WorkflowJobSteps#resolveStepType}: a {@code uses: docker://...} step resolves to
 * {@code "docker"}, any other non-null {@code uses:} value resolves to itself (e.g. {@code
 * "integration"}, {@code "agent"}, {@code "claude-code"}), and otherwise the explicit {@code type:}
 * is used, defaulting to {@code "http"}.
 *
 * @param id              step id, used for {@code ${{ steps.ID.outputs.KEY }}} references
 * @param name             human-readable label
 * @param type              resolved step type (see above) — never null
 * @param ifCondition       the step's {@code if:} expression, or null
 * @param continueOnError   parsed {@code continue-on-error:} (consumed starting Phase 3)
 * @param with              the step's {@code with:} block, or an empty map if absent
 * @param artifacts         the step's declared {@code artifacts:} list (docker/claude-code steps
 *                          only — enforced by {@code WorkflowValidator}, not here), or empty if absent
 * @param raw               the step's full source map, verbatim, for fields not modeled above
 *                          (e.g. {@code outputs:}, {@code url:}, kestra's {@code namespace:}/{@code
 *                          flow_id:}, which stay executor-specific rather than promoted to fields here)
 */
public record StepSpec(String id, String name, String type, String ifCondition,
                        boolean continueOnError, Map<String, Object> with,
                        List<ArtifactSpec> artifacts, Map<String, Object> raw) {

    public StepSpec {
        with = Copies.map(with);
        artifacts = Copies.list(artifacts);
        raw = Copies.map(raw);
    }

    /**
     * The map an executor actually reads: the step's raw source map, with {@code with:} params
     * flattened on top when the step uses the {@code uses:}/{@code with:} authoring format —
     * reproducing exactly what {@code WorkflowJobOrchestrator#runStep} did before this type existed,
     * so executors see identical keys regardless of whether a workflow was authored with the legacy
     * flat-key style or {@code uses:}/{@code with:}.
     */
    public Map<String, Object> effectiveConfig() {
        if (!raw.containsKey("uses") || !raw.containsKey("with")) {
            return raw;
        }
        Map<String, Object> effective = new HashMap<>(raw);
        effective.putAll(with);
        return effective;
    }
}
