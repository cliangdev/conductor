package com.conductor.workflow;

import com.conductor.agent.run.AgentExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    public AgentStepExecutor(AgentExecutionService agentExecutionService,
                             AgentRuntimeResolver runtimeResolver,
                             WorkflowInterpolator interpolator,
                             List<AgentStepRuntime> runtimes) {
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

        Integer timeoutMinutes = null;
        Object timeoutObj = withBlock.get("timeout_minutes");
        if (timeoutObj instanceof Number n) {
            timeoutMinutes = n.intValue();
        }

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
                agent, task, agentContext, outputSchema, timeoutMinutes);
        return runtime.run(context, call);
    }
}
