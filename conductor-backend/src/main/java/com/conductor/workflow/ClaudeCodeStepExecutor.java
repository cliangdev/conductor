package com.conductor.workflow;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes {@code uses: claude-code} workflow steps. Parses the step's flat config fields (prompt,
 * allowed_tools, max_turns, timeout_minutes, conductor_mcp, output_schema — all read directly off the
 * step definition, unlike the {@code agent} step's nested {@code with:} block), interpolates the
 * prompt, and delegates the actual container execution to {@link ClaudeCodeContainerRunner} — the same
 * runner the {@code agent} step's {@code claude-code} runtime ({@link ClaudeCodeAgentStepRuntime})
 * uses. See {@link ClaudeCodeContainerRunner}'s javadoc for credentials, crash recovery, and runs-on
 * resolution.
 */
@Component
public class ClaudeCodeStepExecutor implements WorkflowExecutionBackend {

    private static final String STEP_TYPE = "claude-code";

    private final ClaudeCodeContainerRunner runner;
    private final WorkflowInterpolator interpolator;

    public ClaudeCodeStepExecutor(ClaudeCodeContainerRunner runner, WorkflowInterpolator interpolator) {
        this.runner = runner;
        this.interpolator = interpolator;
    }

    @Override
    public String getStepType() { return STEP_TYPE; }

    @Override
    public StepResult execute(StepExecutionContext context) {
        Map<String, Object> stepDef = context.getStepDefinition();
        RuntimeContext ctx = context.getRuntimeContext();

        Object promptObj = stepDef.get("prompt");
        if (promptObj == null || promptObj.toString().isBlank()) {
            return StepResult.failed("", "Step 'prompt' is required for claude-code step");
        }
        String prompt = interpolator.interpolate(promptObj.toString(), ctx);

        Object allowedToolsObj = stepDef.get("allowed_tools");
        String allowedTools = allowedToolsObj != null ? allowedToolsObj.toString() : null;

        Integer maxTurns = null;
        if (stepDef.get("max_turns") instanceof Number n) {
            maxTurns = n.intValue();
        }

        Integer timeoutMinutes = parseIntOrNull(stepDef, "timeout_minutes");
        boolean conductorMcp = getBooleanOrDefault(stepDef, "conductor_mcp", false);

        Object outputSchemaObj = stepDef.get("output_schema");
        @SuppressWarnings("unchecked")
        Map<String, Object> outputSchema = outputSchemaObj instanceof Map
                ? (Map<String, Object>) outputSchemaObj : null;

        List<Map<String, Object>> credentials = parseCredentials(stepDef);
        Map<String, String> extraEnv = interpolateEnv(stepDef, ctx);

        ClaudeCodeContainerRunner.ClaudeCodeInvocation inv = new ClaudeCodeContainerRunner.ClaudeCodeInvocation(
                prompt, allowedTools, maxTurns, timeoutMinutes, conductorMcp, outputSchema, STEP_TYPE,
                credentials, extraEnv);
        return runner.run(context, inv);
    }

    /** {@code credentials:} entries are literal {@code {connector, as}} maps — no interpolation on the
     *  map structure itself, mirroring how {@code with.agent}/{@code with.action} refs work elsewhere. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseCredentials(Map<String, Object> stepDef) {
        Object credentialsObj = stepDef.get("credentials");
        if (!(credentialsObj instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map) {
                result.add((Map<String, Object>) entry);
            }
        }
        return result;
    }

    /** Interpolates the step's {@code env:} block values through {@link WorkflowInterpolator}, mirroring
     *  {@code DockerStepExecutor#interpolateEnv}. */
    @SuppressWarnings("unchecked")
    private Map<String, String> interpolateEnv(Map<String, Object> stepDef, RuntimeContext ctx) {
        Map<String, String> result = new LinkedHashMap<>();
        Object envObj = stepDef.get("env");
        if (!(envObj instanceof Map)) {
            return result;
        }
        Map<String, Object> envMap = (Map<String, Object>) envObj;
        for (Map.Entry<String, Object> entry : envMap.entrySet()) {
            String value = entry.getValue() != null
                    ? interpolator.interpolate(entry.getValue().toString(), ctx)
                    : "";
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    /** Lenient (Number or numeric String) — null when absent/unparseable; the runner applies its own
     *  default when null. */
    private Integer parseIntOrNull(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
