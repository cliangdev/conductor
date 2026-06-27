package com.conductor.workflow;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.run.AgentRunRequest;
import com.conductor.agent.run.AgentRunResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes workflow steps of type "agent". Resolves a project-scoped {@link Agent} (by slug, then id),
 * interpolates the {@code task} + {@code context} values, and calls {@link AgentExecutionService} —
 * the engine-agnostic ReAct runner. The result is mapped to step outputs the same way
 * {@link IntegrationStepExecutor} maps connector data: {@code text} (the final answer), {@code data}
 * (the structured JSON serialized), and each top-level structured field as its own output key.
 * Declared {@code outputs:} dot-paths (e.g. {@code body.report}) are extracted exactly like
 * {@link HttpStepExecutor}. Provider credentials are never exposed in workflow YAML.
 */
@Component
public class AgentStepExecutor implements WorkflowExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(AgentStepExecutor.class);
    private static final int MAX_LOG_BYTES = 2_000;
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";

    private final AgentRepository agentRepository;
    private final AgentExecutionService agentExecutionService;
    private final WorkflowInterpolator interpolator;
    private final ObjectMapper objectMapper;

    public AgentStepExecutor(AgentRepository agentRepository,
                             AgentExecutionService agentExecutionService,
                             WorkflowInterpolator interpolator,
                             ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.agentExecutionService = agentExecutionService;
        this.interpolator = interpolator;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepType() { return "agent"; }

    @Override
    public StepResult execute(StepExecutionContext context) {
        Map<String, Object> stepDef = context.getStepDefinition();
        RuntimeContext ctx = context.getRuntimeContext();
        String projectId = context.getProjectId();

        @SuppressWarnings("unchecked")
        Map<String, Object> withBlock = (Map<String, Object>) stepDef.get("with");
        if (withBlock == null) {
            return StepResult.failed("", "Step 'with' block is required for agent step");
        }

        String agentRef = (String) withBlock.get("agent");
        if (agentRef == null || agentRef.isBlank()) {
            return StepResult.failed("", "Step 'with.agent' is required for agent step");
        }

        Object taskObj = withBlock.get("task");
        if (taskObj == null || taskObj.toString().isBlank()) {
            return StepResult.failed("", "Step 'with.task' is required for agent step");
        }
        // Interpolate ${{ }} refs so upstream outputs (e.g. steps.collect.outputs.data) resolve.
        String task = interpolator.interpolate(taskObj.toString(), ctx);

        // Interpolate each context value; non-string values pass through unchanged. Upstream
        // integration outputs are JSON strings — embedding them in the context map is intended
        // (the agent reads the JSON in its prompt).
        Map<String, Object> agentContext = new LinkedHashMap<>();
        Object contextObj = withBlock.get("context");
        if (contextObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawContext = (Map<String, Object>) contextObj;
            rawContext.forEach((k, v) ->
                    agentContext.put(k, v instanceof String s ? interpolator.interpolate(s, ctx) : v));
        }

        Map<String, Object> outputSchema = null;
        Object schemaObj = withBlock.get("output_schema");
        if (schemaObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sm = (Map<String, Object>) schemaObj;
            outputSchema = sm;
        }

        // Resolve the Agent: slug first (the YAML-friendly reference), then id — both project-scoped.
        Agent agent = agentRepository.findByProjectIdAndSlug(projectId, agentRef)
                .or(() -> agentRepository.findById(agentRef)
                        .filter(a -> projectId.equals(a.getProjectId())))
                .orElse(null);
        if (agent == null) {
            return StepResult.failed("No agent found: " + agentRef, "Agent not found: " + agentRef);
        }

        try {
            AgentRunResult result = agentExecutionService.run(
                    new AgentRunRequest(agent.getId(), task, agentContext, outputSchema));

            String text = result.outputText() == null ? "" : result.outputText();
            Map<String, Object> structured = result.structuredJson();

            Map<String, String> outputs = new HashMap<>();
            outputs.put("text", text);
            if (structured != null) {
                outputs.put("data", objectMapper.writeValueAsString(structured));
                structured.forEach((k, v) -> {
                    try {
                        outputs.put(k, v instanceof String s ? s : objectMapper.writeValueAsString(v));
                    } catch (Exception ignored) {}
                });
            }
            // Honor declared `outputs:` dot-paths (body.X) like the http/kestra executors.
            applyDeclaredOutputs(stepDef, text, structured, outputs);

            String stepLog = "→ agent=" + agent.getSlug() + " run=" + result.runId()
                    + "\n← " + result.status()
                    + " tokens(in/out)=" + tokenSummary(result)
                    + "\n" + truncate(text);

            if (!STATUS_SUCCEEDED.equals(result.status())) {
                return StepResult.failed(stepLog, "Agent run did not succeed: " + result.status());
            }
            return StepResult.success(stepLog, outputs);

        } catch (Exception e) {
            log.warn("AgentStepExecutor failed for agent={}: {}", agentRef, e.getMessage());
            return StepResult.failed("Agent run failed: " + e.getMessage(), e.getMessage());
        }
    }

    /**
     * Extracts declared {@code outputs:} dot-paths from the agent result, mirroring
     * {@link HttpStepExecutor}. The "body" root combines the structured JSON fields with the
     * top-level {@code text} and {@code data} keys, so {@code body.report}, {@code body.text}, etc.
     * all resolve.
     */
    private void applyDeclaredOutputs(Map<String, Object> stepDef, String text,
                                      Map<String, Object> structured, Map<String, String> outputs) {
        Object outputsObj = stepDef.get("outputs");
        if (!(outputsObj instanceof Map)) return;

        ObjectNode body = structured != null
                ? objectMapper.valueToTree(structured)
                : objectMapper.createObjectNode();
        body.put("text", text);
        if (outputs.containsKey("data")) {
            body.put("data", outputs.get("data"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> outputDefs = (Map<String, Object>) outputsObj;
        for (Map.Entry<String, Object> entry : outputDefs.entrySet()) {
            if (entry.getValue() == null) continue;
            String value = extractJsonPath(body, entry.getValue().toString());
            if (value != null) outputs.put(entry.getKey(), value);
        }
    }

    /** Simple dot-notation JSONPath extraction (body.field.subfield) — matches HttpStepExecutor. */
    private String extractJsonPath(JsonNode root, String path) {
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

    private String tokenSummary(AgentRunResult result) {
        return result.usage() == null
                ? "0/0"
                : result.usage().inputTokens() + "/" + result.usage().outputTokens();
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > MAX_LOG_BYTES ? s.substring(0, MAX_LOG_BYTES) + "\n[truncated]" : s;
    }
}
