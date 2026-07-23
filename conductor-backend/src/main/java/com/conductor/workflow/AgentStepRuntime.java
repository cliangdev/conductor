package com.conductor.workflow;

import com.conductor.agent.run.AgentExecutionService;

import java.util.List;
import java.util.Map;

/**
 * SPI for one way to execute an {@code agent} workflow step's {@code with:} call once the agent
 * {@link AgentExecutionService.AgentDefinition} and the runtime to use have both been resolved
 * ({@link AgentStepExecutor} does the parsing/resolution; implementations only run the call).
 * Discovered via Spring list injection and routed by {@link #id()}, exactly like
 * {@link WorkflowExecutionBackend} beans are collected by {@code WorkflowJobOrchestrator}.
 *
 * <p>Two runtimes ship: {@code "api"} ({@link ApiAgentStepRuntime} — the in-process ReAct loop against
 * an Anthropic API key) and {@code "claude-code"} ({@link ClaudeCodeAgentStepRuntime} — a headless
 * Claude Code container under subscription OAuth). The runtime is never declared in workflow YAML; it
 * is a property of the {@code Agent} definition (or auto-detected from project credentials) — see
 * {@link AgentRuntimeResolver}.
 */
public interface AgentStepRuntime {

    /** Runtime id this implementation handles: {@code "api"} or {@code "claude-code"}. */
    String id();

    /** Runs the agent step call under this runtime. Must not throw — return a failed {@link StepResult}. */
    StepResult run(StepExecutionContext context, AgentStepCall call);

    /**
     * A parsed, interpolated {@code agent} step invocation, ready for a runtime to execute.
     *
     * @param agent           the resolved agent definition (prompt, tools, guardrails, provider).
     * @param task            the interpolated task instruction.
     * @param agentContext    interpolated {@code with.context} map (may be empty, never null).
     * @param outputSchema    optional {@code with.output_schema}, or null.
     * @param timeoutMinutes  optional {@code with.timeout_minutes}, or null (runtime picks its own default).
     * @param credentials     {@code with.credentials} entries ({@code {connector, as}} maps), or empty.
     *                        Only the {@code claude-code} runtime can honor these — see {@link
     *                        ApiAgentStepRuntime}, which fails fast when this is non-empty.
     * @param extraEnv        interpolated {@code with.env} map, or empty. Same caveat as {@code credentials}.
     */
    record AgentStepCall(
            AgentExecutionService.AgentDefinition agent,
            String task,
            Map<String, Object> agentContext,
            Map<String, Object> outputSchema,
            Integer timeoutMinutes,
            List<Map<String, Object>> credentials,
            Map<String, String> extraEnv) {}
}
