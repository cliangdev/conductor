package com.conductor.workflow;

import com.conductor.entity.Connection;
import com.conductor.integration.ActionResult;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.ActiveConnectionResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Executes workflow steps of type "action". Resolves the ACTIVE connection for the given
 * connectorId (like {@link IntegrationStepExecutor}), interpolates {@code with.input} values
 * (like {@link AgentStepExecutor}'s {@code context} block — the orchestrator does not
 * pre-interpolate {@code with:}, executors do it themselves), and calls
 * {@link ActionInvocationService#invoke} with an idempotency key derived from this job run + step,
 * so re-running a job never double-fires the same action.
 */
@Component
public class ActionStepExecutor implements WorkflowExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(ActionStepExecutor.class);

    private final ActiveConnectionResolver activeConnectionResolver;
    private final ActionInvocationService actionInvocationService;
    private final WorkflowInterpolator interpolator;
    private final ObjectMapper objectMapper;

    public ActionStepExecutor(ActiveConnectionResolver activeConnectionResolver,
                              ActionInvocationService actionInvocationService,
                              WorkflowInterpolator interpolator,
                              ObjectMapper objectMapper) {
        this.activeConnectionResolver = activeConnectionResolver;
        this.actionInvocationService = actionInvocationService;
        this.interpolator = interpolator;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepType() { return "action"; }

    @Override
    public StepResult execute(StepExecutionContext context) {
        Map<String, Object> stepDef = context.getStepDefinition();
        RuntimeContext ctx = context.getRuntimeContext();
        String projectId = context.getProjectId();

        @SuppressWarnings("unchecked")
        Map<String, Object> withBlock = (Map<String, Object>) stepDef.get("with");
        if (withBlock == null) {
            return StepResult.failed("", "Step 'with' block is required for action step");
        }
        String connectorId = (String) withBlock.get("connector");
        if (connectorId == null || connectorId.isBlank()) {
            return StepResult.failed("", "Step 'with.connector' is required for action step");
        }
        String actionId = (String) withBlock.get("action");
        if (actionId == null || actionId.isBlank()) {
            return StepResult.failed("", "Step 'with.action' is required for action step");
        }

        Connection conn = activeConnectionResolver.resolve(projectId, connectorId).orElse(null);
        if (conn == null) {
            return StepResult.failed(
                    "No active connection found for connector: " + connectorId,
                    "Integration not connected: " + connectorId);
        }

        // The orchestrator hands executors the raw `with:` map, unlike other interpolated fields
        // (url, body) — each executor interpolates its own with-values, matching AgentStepExecutor.
        Map<String, Object> input = new HashMap<>();
        Object inputObj = withBlock.get("input");
        if (inputObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawInput = (Map<String, Object>) inputObj;
            rawInput.forEach((k, v) ->
                    input.put(k, v instanceof String s ? interpolator.interpolate(s, ctx) : v));
        }

        if (context.getJobRun() == null || context.getJobRun().getId() == null) {
            return StepResult.failed("", "action step requires a job run id for idempotency");
        }
        String jobRunId = context.getJobRun().getId();
        String stepId = (String) stepDef.get("id");
        String idempotencyKey = "wfstep:" + jobRunId + ":" + stepId;

        ActionResult result = actionInvocationService.invoke(conn, actionId, input, idempotencyKey, ctx.getSecrets().values());

        String stepLog = "→ action connector=" + connectorId + " action=" + actionId + " connection=" + conn.getId();
        if (!result.success()) {
            String message = result.message() != null ? result.message() : "Action failed";
            log.warn("ActionStepExecutor failed for connector={} action={}: {}", connectorId, actionId, message);
            return StepResult.failed(stepLog + "\n✗ " + message, message);
        }

        Map<String, String> outputs = new HashMap<>();
        Map<String, Object> output = result.output();
        if (output != null) {
            output.forEach((k, v) -> {
                try {
                    outputs.put(k, v instanceof String s ? s : objectMapper.writeValueAsString(v));
                } catch (Exception ignored) {}
            });
        }
        applyDeclaredOutputs(stepDef, output, outputs);

        return StepResult.success(stepLog + "\n← ok", outputs);
    }

    /** Extracts declared {@code outputs:} dot-paths, mirroring {@link IntegrationStepExecutor}/{@link AgentStepExecutor}. */
    private void applyDeclaredOutputs(Map<String, Object> stepDef, Map<String, Object> output, Map<String, String> outputs) {
        JsonNode body = output != null ? objectMapper.valueToTree(output) : objectMapper.createObjectNode();
        StepOutputMapper.applyDeclaredOutputs(stepDef, body, outputs);
    }
}
