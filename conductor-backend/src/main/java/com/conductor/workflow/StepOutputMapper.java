package com.conductor.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Shared declared-{@code outputs:} dot-path extraction, used by any step executor whose result is
 * naturally a JSON body (agent structured output, Cloud Run container outputs). Extracted from
 * {@link AgentStepExecutor} so {@link ClaudeCodeStepExecutor} doesn't duplicate it — behavior is
 * unchanged from the original agent-only implementation, mirrors {@link HttpStepExecutor}/
 * {@link KestraStepExecutor}'s own (independent) dot-path extractors.
 */
final class StepOutputMapper {

    private StepOutputMapper() {
    }

    /**
     * Extracts declared {@code outputs:} dot-paths (e.g. {@code body.report}) from {@code body} and
     * merges them into {@code outputs} (mutated in place). No-op when the step defines no
     * {@code outputs:} block.
     */
    static void applyDeclaredOutputs(Map<String, Object> stepDef, JsonNode body, Map<String, String> outputs) {
        Object outputsObj = stepDef.get("outputs");
        if (!(outputsObj instanceof Map)) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> outputDefs = (Map<String, Object>) outputsObj;
        for (Map.Entry<String, Object> entry : outputDefs.entrySet()) {
            if (entry.getValue() == null) continue;
            String value = extractJsonPath(body, entry.getValue().toString());
            if (value != null) outputs.put(entry.getKey(), value);
        }
    }

    /** Simple dot-notation JSONPath extraction (body.field.subfield). */
    static String extractJsonPath(JsonNode root, String path) {
        if (path == null) return null;
        String cleanPath = path.startsWith("body.") ? path.substring(5) : path;
        String[] parts = cleanPath.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null || current.isNull()) return null;
            current = current.get(part);
        }
        if (current == null || current.isNull()) return null;
        return current.isTextual() ? current.asText() : current.toString();
    }
}
