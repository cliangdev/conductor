package com.conductor.workflow;

import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.tool.AgentToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes workflow steps of type "agent". Parses and interpolates the {@code with:} block (agent
 * ref, task, context, output_schema, timeout_minutes) exactly as before, resolves the referenced
 * {@link com.conductor.agent.Agent} definition via {@link AgentExecutionService#resolveDefinition},
 * resolves which runtime it runs under via {@link AgentRuntimeResolver}, and delegates the actual call
 * to the matching {@link AgentStepRuntime}. The runtime — {@code api} (in-process ReAct loop) or
 * {@code claude-code} (headless Claude Code container) — is never declared in workflow YAML; it is a
 * property of the agent definition (or auto-detected from project credentials).
 */
@Component
public class AgentStepExecutor implements WorkflowExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(AgentStepExecutor.class);

    private final AgentExecutionService agentExecutionService;
    private final AgentRuntimeResolver runtimeResolver;
    private final WorkflowInterpolator interpolator;
    private final Map<String, AgentStepRuntime> runtimesById;
    private final AgentToolRegistry toolRegistry;

    public AgentStepExecutor(AgentExecutionService agentExecutionService,
                             AgentRuntimeResolver runtimeResolver,
                             WorkflowInterpolator interpolator,
                             List<AgentStepRuntime> runtimes) {
        this(agentExecutionService, runtimeResolver, interpolator, runtimes, null);
    }

    @Autowired
    public AgentStepExecutor(AgentExecutionService agentExecutionService,
                             AgentRuntimeResolver runtimeResolver,
                             WorkflowInterpolator interpolator,
                             List<AgentStepRuntime> runtimes,
                             AgentToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.agentExecutionService = agentExecutionService;
        this.runtimeResolver = runtimeResolver;
        this.interpolator = interpolator;
        Map<String, AgentStepRuntime> byId = new HashMap<>();
        for (AgentStepRuntime rt : runtimes) byId.put(rt.id(), rt);
        this.runtimesById = byId;
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

        String rawAgentRef = (String) withBlock.get("agent");
        if (rawAgentRef == null || rawAgentRef.isBlank()) {
            return StepResult.failed("", "Step 'with.agent' is required for agent step");
        }
        // Interpolate ${{ }} refs (e.g. ${{ event.agentSlug }}) so dispatch-time agent selection works;
        // a literal slug has no ${{ }} pattern so it passes through unchanged (backward compatible).
        String agentRef = interpolator.interpolate(rawAgentRef, ctx);
        if (agentRef.isBlank()) {
            // Name the unresolved expression rather than just "empty" — WorkflowInterpolator silently
            // resolves any unknown/missing reference to "", so this is the only signal of which field
            // was missing and where it should have come from (this run's trigger payload, a prior
            // step/job output, etc).
            return StepResult.failed("", "Step 'with.agent' resolved to empty from '" + rawAgentRef.trim()
                    + "' — the referenced field wasn't present in this run's data. If this workflow expects "
                    + "an automated trigger to supply it, running it manually won't have that data.");
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

        Integer timeoutMinutes = null;
        Object timeoutObj = withBlock.get("timeout_minutes");
        if (timeoutObj instanceof Number n) {
            timeoutMinutes = n.intValue();
        }

        List<String> grantTools;
        try {
            grantTools = parseGrantTools(withBlock, projectId);
        } catch (IllegalArgumentException e) {
            return StepResult.failed("", e.getMessage());
        }
        List<Map<String, Object>> credentials = parseCredentials(withBlock);
        Map<String, String> extraEnv = interpolateEnv(withBlock, ctx);

        AgentExecutionService.AgentDefinition agent;
        try {
            agent = agentExecutionService.resolveDefinition(projectId, agentRef);
        } catch (Exception e) {
            log.warn("AgentStepExecutor: could not resolve agent={}: {}", agentRef, e.getMessage());
            return StepResult.failed("Agent run failed: " + e.getMessage(), e.getMessage());
        }

        String runtimeId;
        try {
            runtimeId = runtimeResolver.resolve(projectId, agent);
        } catch (AgentRuntimeUnresolvedException e) {
            return StepResult.failed("", e.getMessage());
        }

        AgentStepRuntime runtime = runtimesById.get(runtimeId);
        if (runtime == null) {
            return StepResult.failed("", "Unknown agent runtime: " + runtimeId);
        }

        log.info("agent step: agent={} runtime={}", agentRef, runtimeId);
        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(
                agent, task, agentContext, outputSchema, timeoutMinutes, credentials, extraEnv, grantTools);
        return runtime.run(context, call);
    }

    /**
     * {@code with.grant_tools}: tool ids this one run may use in addition to the agent's own {@code toolIds}
     * (the agent definition is shared, persisted state and is never changed by a step). Each must be a
     * namespaced id ({@code source:name}) that the tool registry resolves for this project right now, so a
     * workflow can only grant what the project could otherwise bind to an agent: an unknown id, or a source
     * that is switched off for the project (the Knowledge Center when disabled), fails the step before anything
     * runs. Literal ids; not interpolated.
     */
    List<String> parseGrantTools(Map<String, Object> withBlock, String projectId) {
        Object raw = withBlock.get("grant_tools");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("Step 'with.grant_tools' must be a list of tool ids");
        }
        List<String> ids = new ArrayList<>();
        for (Object entry : list) {
            String id = entry instanceof String s ? s.trim() : "";
            if (id.isEmpty() || id.indexOf(':') <= 0) {
                throw new IllegalArgumentException(
                        "Step 'with.grant_tools' entries must be namespaced tool ids like 'comment:list_comments'");
            }
            if (toolRegistry == null || toolRegistry.resolve(projectId, id).isEmpty()) {
                throw new IllegalArgumentException("GRANT_TOOL_UNAVAILABLE: '" + id
                        + "' is not a tool this project can use (unknown id, or its source is switched off)");
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** {@code with.credentials} entries are literal {@code {connector, as}} maps — no interpolation on
     *  the map structure itself, mirroring {@link ClaudeCodeStepExecutor}'s identical parsing. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseCredentials(Map<String, Object> withBlock) {
        Object credentialsObj = withBlock.get("credentials");
        if (!(credentialsObj instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map) {
                result.add((Map<String, Object>) entry);
            }
        }
        return result;
    }

    /** Interpolates {@code with.env} values through {@link WorkflowInterpolator}, mirroring {@link
     *  ClaudeCodeStepExecutor}/{@code DockerStepExecutor}'s identical env-interpolation approach. */
    @SuppressWarnings("unchecked")
    private Map<String, String> interpolateEnv(Map<String, Object> withBlock, RuntimeContext ctx) {
        Map<String, String> result = new LinkedHashMap<>();
        Object envObj = withBlock.get("env");
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
}
