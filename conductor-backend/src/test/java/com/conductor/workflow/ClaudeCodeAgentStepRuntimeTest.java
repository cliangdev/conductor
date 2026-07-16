package com.conductor.workflow;

import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.tool.AgentToolRegistry;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaudeCodeAgentStepRuntimeTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock
    private ClaudeCodeContainerRunner runner;
    @Mock
    private AgentToolRegistry toolRegistry;

    private ClaudeCodeAgentStepRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new ClaudeCodeAgentStepRuntime(runner, toolRegistry,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private StepExecutionContext context() {
        return new StepExecutionContext(new WorkflowRun(), new WorkflowJobRun(),
                Map.of("id", "librarian"), new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of()), PROJECT_ID);
    }

    private AgentExecutionService.AgentDefinition definition(List<String> toolIds, Integer maxToolTurns) {
        return new AgentExecutionService.AgentDefinition(
                "agent-1", "knowledge-librarian", "claude", null, "You are the librarian.", toolIds, maxToolTurns, null);
    }

    @Test
    void idReturnsClaudeCode() {
        assertThat(runtime.id()).isEqualTo("claude-code");
    }

    @Test
    void promptConcatenatesSystemPromptAndTask() {
        AgentExecutionService.AgentDefinition agent = definition(List.of(), 8);
        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(
                agent, "File the batch.", Map.of(), null, null);
        when(runner.run(any(), any())).thenReturn(StepResult.success("ok", Map.of()));

        runtime.run(context(), call);

        ArgumentCaptor<ClaudeCodeContainerRunner.ClaudeCodeInvocation> captor =
                ArgumentCaptor.forClass(ClaudeCodeContainerRunner.ClaudeCodeInvocation.class);
        verify(runner).run(any(), captor.capture());
        assertThat(captor.getValue().prompt()).isEqualTo("You are the librarian.\n\n# Task\n\nFile the batch.");
    }

    @Test
    void contextMapAppendedToPromptAsJsonBlock() {
        AgentExecutionService.AgentDefinition agent = definition(List.of(), 8);
        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(
                agent, "File the batch.", Map.of("repo", "cliangdev/conductor"), null, null);
        when(runner.run(any(), any())).thenReturn(StepResult.success("ok", Map.of()));

        runtime.run(context(), call);

        ArgumentCaptor<ClaudeCodeContainerRunner.ClaudeCodeInvocation> captor =
                ArgumentCaptor.forClass(ClaudeCodeContainerRunner.ClaudeCodeInvocation.class);
        verify(runner).run(any(), captor.capture());
        assertThat(captor.getValue().prompt())
                .startsWith("You are the librarian.\n\n# Task\n\nFile the batch.\n\n## Context\n```json\n")
                .contains("\"repo\" : \"cliangdev/conductor\"")
                .endsWith("\n```");
    }

    @Test
    void toolIdsMapToClaudeCodeAllowedToolsCommaJoined() {
        AgentExecutionService.AgentDefinition agent = definition(
                List.of("knowledge:read_knowledge_pages", "knowledge:write_knowledge_pages"), 8);
        when(toolRegistry.claudeCodeToolName("knowledge:read_knowledge_pages"))
                .thenReturn(Optional.of("mcp__conductor__read_knowledge_pages"));
        when(toolRegistry.claudeCodeToolName("knowledge:write_knowledge_pages"))
                .thenReturn(Optional.of("mcp__conductor__write_knowledge_pages"));
        when(runner.run(any(), any())).thenReturn(StepResult.success("ok", Map.of()));

        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(agent, "task", Map.of(), null, null);
        runtime.run(context(), call);

        ArgumentCaptor<ClaudeCodeContainerRunner.ClaudeCodeInvocation> captor =
                ArgumentCaptor.forClass(ClaudeCodeContainerRunner.ClaudeCodeInvocation.class);
        verify(runner).run(any(), captor.capture());
        assertThat(captor.getValue().allowedTools())
                .isEqualTo("mcp__conductor__read_knowledge_pages,mcp__conductor__write_knowledge_pages");
    }

    @Test
    void unmappableToolIdFailsFastWithoutCallingRunner() {
        AgentExecutionService.AgentDefinition agent = definition(List.of("http:some-tool"), 8);
        when(toolRegistry.claudeCodeToolName("http:some-tool")).thenReturn(Optional.empty());

        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(agent, "task", Map.of(), null, null);
        StepResult result = runtime.run(context(), call);

        assertThat(result.getStatus()).isEqualTo(com.conductor.entity.WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).isEqualTo("AGENT_TOOL_NOT_AVAILABLE_ON_CLAUDE_CODE: http:some-tool");
        verifyNoInteractions(runner);
    }

    @Test
    void emptyToolIdsYieldsNullAllowedTools() {
        AgentExecutionService.AgentDefinition agent = definition(List.of(), 8);
        when(runner.run(any(), any())).thenReturn(StepResult.success("ok", Map.of()));

        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(agent, "task", Map.of(), null, null);
        runtime.run(context(), call);

        ArgumentCaptor<ClaudeCodeContainerRunner.ClaudeCodeInvocation> captor =
                ArgumentCaptor.forClass(ClaudeCodeContainerRunner.ClaudeCodeInvocation.class);
        verify(runner).run(any(), captor.capture());
        assertThat(captor.getValue().allowedTools()).isNull();
        assertThat(captor.getValue().conductorMcp()).isFalse();
    }

    @Test
    void maxToolTurnsMapsToInvocationMaxTurns() {
        AgentExecutionService.AgentDefinition agent = definition(List.of(), 25);
        when(runner.run(any(), any())).thenReturn(StepResult.success("ok", Map.of()));

        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(agent, "task", Map.of(), null, null);
        runtime.run(context(), call);

        ArgumentCaptor<ClaudeCodeContainerRunner.ClaudeCodeInvocation> captor =
                ArgumentCaptor.forClass(ClaudeCodeContainerRunner.ClaudeCodeInvocation.class);
        verify(runner).run(any(), captor.capture());
        assertThat(captor.getValue().maxTurns()).isEqualTo(25);
    }

    @Test
    void conductorMcpAutoEnabledWhenAnyMappedToolIsAnMcpTool() {
        AgentExecutionService.AgentDefinition agent = definition(List.of("knowledge:search_knowledge"), 8);
        when(toolRegistry.claudeCodeToolName("knowledge:search_knowledge"))
                .thenReturn(Optional.of("mcp__conductor__search_knowledge"));
        when(runner.run(any(), any())).thenReturn(StepResult.success("ok", Map.of()));

        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(agent, "task", Map.of(), null, null);
        runtime.run(context(), call);

        ArgumentCaptor<ClaudeCodeContainerRunner.ClaudeCodeInvocation> captor =
                ArgumentCaptor.forClass(ClaudeCodeContainerRunner.ClaudeCodeInvocation.class);
        verify(runner).run(any(), captor.capture());
        assertThat(captor.getValue().conductorMcp()).isTrue();
    }

    @Test
    void stepTypeIsAgent() {
        AgentExecutionService.AgentDefinition agent = definition(List.of(), 8);
        when(runner.run(any(), any())).thenReturn(StepResult.success("ok", Map.of()));

        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(agent, "task", Map.of(), null, null);
        runtime.run(context(), call);

        ArgumentCaptor<ClaudeCodeContainerRunner.ClaudeCodeInvocation> captor =
                ArgumentCaptor.forClass(ClaudeCodeContainerRunner.ClaudeCodeInvocation.class);
        verify(runner).run(any(), captor.capture());
        assertThat(captor.getValue().stepType()).isEqualTo("agent");
    }

    @Test
    void delegatesRunnerResultVerbatim() {
        AgentExecutionService.AgentDefinition agent = definition(List.of(), 8);
        StepExecutionContext ctx = context();
        StepResult expected = StepResult.failed("log", "CLAUDE_AGENT_ERROR");
        when(runner.run(eq(ctx), any())).thenReturn(expected);

        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(agent, "task", Map.of(), null, null);
        StepResult result = runtime.run(ctx, call);

        assertThat(result).isSameAs(expected);
    }
}
