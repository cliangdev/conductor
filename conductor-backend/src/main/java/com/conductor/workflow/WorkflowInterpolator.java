package com.conductor.workflow;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves ${{ expr }} expressions in workflow YAML strings against a RuntimeContext.
 * Supports dot-notation paths: event.FIELD, secrets.KEY, steps.ID.outputs.KEY, steps.ID.result,
 * needs.JOB.outputs.KEY, needs.JOB.result, needs.JOB.artifacts.NAME, inputs.KEY.
 * Unknown references resolve to empty string (no exception).
 */
@Component
public class WorkflowInterpolator {

    private static final Pattern EXPR_PATTERN = Pattern.compile("\\$\\{\\{\\s*(.+?)\\s*\\}\\}");

    /**
     * Replaces all ${{ expr }} occurrences in template with resolved values from context.
     */
    public String interpolate(String template, RuntimeContext context) {
        if (template == null) return null;
        Matcher matcher = EXPR_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String expr = matcher.group(1).trim();
            String value = resolve(expr, context);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Resolves a bare expression (without ${{ }}) to a string value.
     * Returns empty string if reference is not found.
     */
    public String resolve(String expr, RuntimeContext context) {
        try {
            if (expr.startsWith("loop.")) {
                String key = expr.substring("loop.".length());
                if ("iteration".equals(key)) {
                    return String.valueOf(context.getLoopIteration());
                }
                return "";
            } else if (expr.startsWith("event.")) {
                String key = expr.substring("event.".length());
                Object val = context.getEventPayload().get(key);
                return val != null ? val.toString() : "";
            } else if (expr.startsWith("secrets.")) {
                String key = expr.substring("secrets.".length());
                return context.getSecrets().getOrDefault(key, "");
            } else if (expr.startsWith("steps.")) {
                // steps.STEP_ID.outputs.KEY or steps.STEP_ID.result
                String[] parts = expr.split("\\.", 4);
                if (parts.length == 4 && "outputs".equals(parts[2])) {
                    String stepId = parts[1];
                    String outputKey = parts[3];
                    Map<String, String> outputs = context.getStepOutputs().get(stepId);
                    return outputs != null ? outputs.getOrDefault(outputKey, "") : "";
                }
                if (parts.length == 3 && "result".equals(parts[2])) {
                    return context.getStepResults().getOrDefault(parts[1], "");
                }
            } else if (expr.startsWith("needs.")) {
                // needs.JOB_ID.outputs.KEY or needs.JOB_ID.result or needs.JOB_ID.artifacts.NAME
                String[] parts = expr.split("\\.", 4);
                if (parts.length == 4 && "outputs".equals(parts[2])) {
                    String jobId = parts[1];
                    String outputKey = parts[3];
                    Map<String, String> outputs = context.getJobOutputs().get(jobId);
                    return outputs != null ? outputs.getOrDefault(outputKey, "") : "";
                }
                if (parts.length == 4 && "artifacts".equals(parts[2])) {
                    String jobId = parts[1];
                    String artifactName = parts[3];
                    Map<String, String> artifacts = context.getJobArtifacts().get(jobId);
                    return artifacts != null ? artifacts.getOrDefault(artifactName, "") : "";
                }
                if (parts.length == 3 && "result".equals(parts[2])) {
                    return context.getJobResults().getOrDefault(parts[1], "");
                }
            } else if (expr.startsWith("inputs.")) {
                String key = expr.substring("inputs.".length());
                return context.getInputs().getOrDefault(key, "");
            }
        } catch (Exception e) {
            // Swallow — return empty string
        }
        return "";
    }
}
