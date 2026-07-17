package com.conductor.workflow;

import com.conductor.agent.provider.TokenUsage;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.run.AgentRunResult;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Output-mapping/status cases relocated from the old (pre-runtime-split) {@code AgentStepExecutorTest}
 * — this runtime now owns exactly that logic; {@link AgentStepExecutorTest} covers only parsing and
 * routing to the resolver-selected runtime.
 */
@ExtendWith(MockitoExtension.class)
class ApiAgentStepRuntimeTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock
    private AgentExecutionService agentExecutionService;

    private ApiAgentStepRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new ApiAgentStepRuntime(agentExecutionService, new ObjectMapper());
    }

    private AgentExecutionService.AgentDefinition definition() {
        return new AgentExecutionService.AgentDefinition(
                "agent-1", "marketing-agent", "claude", null, "You are helpful.", List.of(), 8, null);
    }

    private StepExecutionContext context(Map<String, Object> stepDef) {
        return new StepExecutionContext(new WorkflowRun(), new WorkflowJobRun(),
                stepDef, new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of()), PROJECT_ID);
    }

    private Map<String, Object> stepDef() {
        Map<String, Object> stepDef = new LinkedHashMap<>();
        stepDef.put("id", "analyze");
        stepDef.put("uses", "agent");
        return stepDef;
    }

    @Test
    void idReturnsApi() {
        assertThat(runtime.id()).isEqualTo("api");
    }

    @Test
    void successfulRunMapsTextAndStructuredFieldsToOutputs() {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("report", "SEO is healthy");
        structured.put("action_items", List.of("Fix titles", "Add alt text"));
        when(agentExecutionService.run(eq(PROJECT_ID), eq("marketing-agent"), anyString(), anyMap(), any()))
                .thenReturn(new AgentRunResult("run-9", "Final answer text", structured,
                        new TokenUsage(100, 50), "SUCCEEDED"));

        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(
                definition(), "Analyze SEO health", Map.of(), null, null);
        StepResult result = runtime.run(context(stepDef()), call);

        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(result.getOutputs().get("text")).isEqualTo("Final answer text");
        assertThat(result.getOutputs().get("report")).isEqualTo("SEO is healthy");
        assertThat(result.getOutputs().get("action_items")).contains("Fix titles");
        assertThat(result.getOutputs()).containsKey("data");
        assertThat(result.getLog()).contains("runtime=api");
    }

    @Test
    void agentRunExceptionYieldsFailed() {
        when(agentExecutionService.run(eq(PROJECT_ID), eq("ghost"), anyString(), anyMap(), any()))
                .thenThrow(new EntityNotFoundException("Agent not found: ghost"));

        AgentExecutionService.AgentDefinition ghost = new AgentExecutionService.AgentDefinition(
                "id", "ghost", "claude", null, null, List.of(), 8, null);
        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(
                ghost, "Do something", Map.of(), null, null);
        StepResult result = runtime.run(context(stepDef()), call);

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("Agent not found: ghost");
    }

    @Test
    void declaredOutputsDotPathExtractsStructuredFields() {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("report", "All good");
        when(agentExecutionService.run(eq(PROJECT_ID), eq("marketing-agent"), anyString(), anyMap(), any()))
                .thenReturn(new AgentRunResult("run-2", "final", structured, TokenUsage.ZERO, "SUCCEEDED"));

        Map<String, Object> stepDef = stepDef();
        stepDef.put("outputs", Map.of("summary", "body.report"));
        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(
                definition(), "Analyze", Map.of(), null, null);
        StepResult result = runtime.run(context(stepDef), call);

        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(result.getOutputs().get("summary")).isEqualTo("All good");
    }

    @Test
    void nonSucceededStatusYieldsFailed() {
        when(agentExecutionService.run(eq(PROJECT_ID), eq("marketing-agent"), anyString(), anyMap(), any()))
                .thenReturn(new AgentRunResult("run-3", "partial", null, TokenUsage.ZERO, "FAILED"));

        AgentStepRuntime.AgentStepCall call = new AgentStepRuntime.AgentStepCall(
                definition(), "Analyze", Map.of(), null, null);
        StepResult result = runtime.run(context(stepDef()), call);

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("FAILED");
    }
}
