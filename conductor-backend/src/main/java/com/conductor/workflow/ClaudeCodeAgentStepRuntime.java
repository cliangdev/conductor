package com.conductor.workflow;

import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.tool.AgentToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code AgentStepRuntime} id {@code "claude-code"} — runs an {@code agent} step's call as a headless
 * Claude Code container via {@link ClaudeCodeContainerRunner}, the same runner {@link
 * ClaudeCodeStepExecutor} uses for raw {@code claude-code} steps. The container entrypoint has no
 * separate system-prompt channel, so the agent's {@code systemPrompt} is prepended to the task as one
 * prompt. Each of the agent's bound tool ids is mapped to a Claude Code {@code --allowedTools} name via
 * {@link AgentToolRegistry#claudeCodeToolName} — any tool that has no Claude Code equivalent fails the
 * step fast rather than silently running with fewer tools than the agent was configured with.
 */
@Component
public class ClaudeCodeAgentStepRuntime implements AgentStepRuntime {

    private final ClaudeCodeContainerRunner runner;
    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public ClaudeCodeAgentStepRuntime(ClaudeCodeContainerRunner runner, AgentToolRegistry toolRegistry,
                                      ObjectMapper objectMapper) {
        this.runner = runner;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return AgentRuntimeResolver.RUNTIME_CLAUDE_CODE;
    }

    @Override
    public StepResult run(StepExecutionContext context, AgentStepCall call) {
        AgentExecutionService.AgentDefinition agent = call.agent();

        List<String> toolNames = new ArrayList<>();
        for (String toolId : agent.toolIds()) {
            Optional<String> mapped = toolRegistry.claudeCodeToolName(toolId);
            if (mapped.isEmpty()) {
                return StepResult.failed("", "AGENT_TOOL_NOT_AVAILABLE_ON_CLAUDE_CODE: " + toolId);
            }
            toolNames.add(mapped.get());
        }
        String allowedTools = toolNames.isEmpty() ? null : String.join(",", toolNames);
        boolean conductorMcp = toolNames.stream().anyMatch(n -> n.startsWith("mcp__conductor__"));

        String systemPrompt = agent.systemPrompt() == null ? "" : agent.systemPrompt();
        String prompt = systemPrompt + "\n\n# Task\n\n" + call.task() + contextSection(call.agentContext());

        ClaudeCodeContainerRunner.ClaudeCodeInvocation inv = new ClaudeCodeContainerRunner.ClaudeCodeInvocation(
                prompt, allowedTools, agent.maxToolTurns(), call.timeoutMinutes(), conductorMcp,
                call.outputSchema(), "agent", call.credentials(), call.extraEnv());
        try {
            return runner.run(context, inv);
        } catch (Exception e) {
            // SPI contract: never throw. An escaped exception isn't persisted as a FAILED step — the
            // orchestrator doesn't catch, so the job run would hang RUNNING until stuck-job recovery.
            return StepResult.failed("Agent run failed: " + e.getMessage(), e.getMessage());
        }
    }

    /** Same {@code ## Context} JSON block {@link AgentExecutionService} appends to the api runtime's
     *  user message — a step's {@code context:} map must survive a runtime switch. */
    private String contextSection(Map<String, Object> agentContext) {
        if (agentContext == null || agentContext.isEmpty()) {
            return "";
        }
        try {
            return "\n\n## Context\n```json\n"
                    + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(agentContext)
                    + "\n```";
        } catch (Exception e) {
            return "\n\n## Context\n" + agentContext;
        }
    }
}
