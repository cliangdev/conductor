package com.conductor.workflow;

import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.run.AgentRunResult;
import com.conductor.agent.provider.TokenUsage;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.AgentToolRegistry;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code with.grant_tools}: validated by the executor, carried on the call, honoured by both runtimes. */
@ExtendWith(MockitoExtension.class)
class GrantToolsTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock AgentExecutionService agentExecutionService;
    @Mock AgentRuntimeResolver runtimeResolver;
    @Mock AgentToolRegistry toolRegistry;
    @Mock ClaudeCodeContainerRunner runner;

    private static final class RecordingRuntime implements AgentStepRuntime {
        AgentStepCall lastCall;
        public String id() { return "api"; }
        public StepResult run(StepExecutionContext context, AgentStepCall call) {
            lastCall = call;
            return StepResult.success("ok", Map.of());
        }
    }

    RecordingRuntime runtime;
    AgentStepExecutor executor;

    @BeforeEach
    void setUp() {
        runtime = new RecordingRuntime();
        executor = new AgentStepExecutor(agentExecutionService, runtimeResolver, new WorkflowInterpolator(),
                List.of(runtime), toolRegistry);
    }

    private StepExecutionContext context(Object grantTools) {
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "helper");
        with.put("task", "do it");
        if (grantTools != null) {
            with.put("grant_tools", grantTools);
        }
        Map<String, Object> stepDef = new LinkedHashMap<>();
        stepDef.put("id", "s");
        stepDef.put("uses", "agent");
        stepDef.put("with", with);
        return new StepExecutionContext(new WorkflowRun(), new WorkflowJobRun(), stepDef,
                new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of()), PROJECT_ID);
    }

    private AgentExecutionService.AgentDefinition definition() {
        return new AgentExecutionService.AgentDefinition("a1", "helper", "claude", null, "sys", List.of("x:own"), 8, null);
    }

    private void agentResolves() {
        when(agentExecutionService.resolveDefinition(PROJECT_ID, "helper")).thenReturn(definition());
        when(runtimeResolver.resolve(PROJECT_ID, definition())).thenReturn("api");
    }

    // ---- executor ----

    @Test
    void validGrantedToolsReachTheRuntimeCall() {
        agentResolves();
        when(toolRegistry.resolve(PROJECT_ID, "comment:list_comments")).thenReturn(Optional.of(mock(AgentTool.class)));
        when(toolRegistry.resolve(PROJECT_ID, "comment:read_document")).thenReturn(Optional.of(mock(AgentTool.class)));

        StepResult result = executor.execute(context(List.of("comment:list_comments", "comment:read_document",
                "comment:list_comments")));

        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(runtime.lastCall.grantTools()).containsExactly("comment:list_comments", "comment:read_document");
    }

    @Test
    void noGrantTools_meansNoGrants() {
        agentResolves();
        executor.execute(context(null));
        assertThat(runtime.lastCall.grantTools()).isEmpty();
    }

    @Test
    void aToolTheProjectCannotUseFailsTheStepBeforeAnythingRuns() {
        when(toolRegistry.resolve(PROJECT_ID, "knowledge:write_knowledge_pages")).thenReturn(Optional.empty());

        StepResult result = executor.execute(context(List.of("knowledge:write_knowledge_pages")));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("GRANT_TOOL_UNAVAILABLE").contains("knowledge:write_knowledge_pages");
        assertThat(runtime.lastCall).isNull();
    }

    @Test
    void malformedGrantToolsFailTheStep() {
        assertThat(executor.execute(context("comment:list_comments")).getErrorReason()).contains("must be a list");
        assertThat(executor.execute(context(List.of("no_namespace"))).getErrorReason()).contains("namespaced");
        assertThat(executor.execute(context(List.of(5))).getErrorReason()).contains("namespaced");
    }

    // ---- api runtime ----

    @Test
    void apiRuntimePassesGrantsToTheAgentRunAndOnlyThen() {
        ApiAgentStepRuntime api = new ApiAgentStepRuntime(agentExecutionService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        AgentRunResult ok = new AgentRunResult("r1", "done", null, new TokenUsage(1, 1), "SUCCEEDED");
        when(agentExecutionService.run(eq(PROJECT_ID), eq("helper"), anyString(), anyMap(), any())).thenReturn(ok);
        when(agentExecutionService.run(eq(PROJECT_ID), eq("helper"), anyString(), anyMap(), any(), anyGranted()))
                .thenReturn(ok);

        api.run(context(null), new AgentStepRuntime.AgentStepCall(definition(), "t", Map.of(), null, null,
                List.of(), Map.of(), List.of("comment:list_comments")));
        verify(agentExecutionService).run(eq(PROJECT_ID), eq("helper"), anyString(), anyMap(), any(),
                eq(Set.of("comment:list_comments")));

        api.run(context(null), new AgentStepRuntime.AgentStepCall(definition(), "t", Map.of(), null, null,
                List.of(), Map.of()));
        verify(agentExecutionService).run(eq(PROJECT_ID), eq("helper"), anyString(), anyMap(), any());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> anyGranted() {
        return any(Set.class);
    }

    // ---- claude-code runtime ----

    @Test
    void claudeCodeRuntimeAppendsTheMappedMcpNamesOnceAndEnablesTheConductorMcp() {
        ClaudeCodeAgentStepRuntime cc = new ClaudeCodeAgentStepRuntime(runner, toolRegistry,
                new com.fasterxml.jackson.databind.ObjectMapper());
        when(toolRegistry.claudeCodeToolName("x:own")).thenReturn(Optional.of("mcp__conductor__own"));
        when(toolRegistry.claudeCodeToolName("comment:read_document")).thenReturn(Optional.of("mcp__conductor__read_document"));
        when(toolRegistry.claudeCodeToolName("comment:own_again")).thenReturn(Optional.of("mcp__conductor__own"));
        when(runner.run(any(), any())).thenReturn(StepResult.success("ok", Map.of()));

        cc.run(context(null), new AgentStepRuntime.AgentStepCall(definition(), "t", Map.of(), null, null,
                List.of(), Map.of(), List.of("comment:read_document", "comment:own_again")));

        ArgumentCaptor<ClaudeCodeContainerRunner.ClaudeCodeInvocation> inv =
                ArgumentCaptor.forClass(ClaudeCodeContainerRunner.ClaudeCodeInvocation.class);
        verify(runner).run(any(), inv.capture());
        assertThat(inv.getValue().allowedTools())
                .isEqualTo("mcp__conductor__own,mcp__conductor__read_document");
        assertThat(inv.getValue().conductorMcp()).isTrue();
    }

    @Test
    void aGrantedToolWithNoClaudeCodeEquivalentFailsTheStep() {
        ClaudeCodeAgentStepRuntime cc = new ClaudeCodeAgentStepRuntime(runner, toolRegistry,
                new com.fasterxml.jackson.databind.ObjectMapper());
        when(toolRegistry.claudeCodeToolName("x:own")).thenReturn(Optional.of("mcp__conductor__own"));
        when(toolRegistry.claudeCodeToolName("http:only-api")).thenReturn(Optional.empty());

        StepResult result = cc.run(context(null), new AgentStepRuntime.AgentStepCall(definition(), "t", Map.of(),
                null, null, List.of(), Map.of(), List.of("http:only-api")));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("AGENT_TOOL_NOT_AVAILABLE_ON_CLAUDE_CODE: http:only-api");
    }
}
