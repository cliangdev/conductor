package com.conductor.workflow;

import java.util.List;
import java.util.Map;

/**
 * Shared step-list resolution for self-hosted job dispatch. {@link SelfHostedJobDispatcher}
 * pre-creates one {@code WorkflowStepRun} per entry (index N -> workerJobId "{jobRunId}:N"),
 * and {@link JobDispatchPayloadService} must rebuild the exact same list/order later to line
 * the dispatch payload back up with those pre-created rows — hence a shared helper instead of
 * two independently-drifting copies.
 */
final class WorkflowJobSteps {

    private WorkflowJobSteps() {
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> executableSteps(Map<String, Object> jobDef) {
        Object stepsObj = jobDef.get("steps");
        List<Map<String, Object>> steps = stepsObj instanceof List ? (List<Map<String, Object>>) stepsObj : List.of();
        return steps.stream().filter(s -> !"condition".equals(s.get("type"))).toList();
    }

    static String resolveStepType(Map<String, Object> stepDef) {
        Object usesVal = stepDef.get("uses");
        if (usesVal instanceof String uses) {
            if (uses.startsWith("docker://")) return "docker";
            return uses;
        }
        return (String) stepDef.getOrDefault("type", "http");
    }
}
