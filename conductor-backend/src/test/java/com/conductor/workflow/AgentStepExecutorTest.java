package com.conductor.workflow;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.provider.TokenUsage;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.run.AgentRunRequest;
import com.conductor.agent.run.AgentRunResult;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentStepExecutorTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AgentExecutionService agentExecutionService;

    private AgentStepExecutor executor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        executor = new AgentStepExecutor(agentRepository, agentExecutionService,
                new WorkflowInterpolator(), objectMapper);
    }

    private Agent agent(String id, String slug) {
        Agent a = new Agent();
        a.setId(id);
        a.setSlug(slug);
        a.setProjectId(PROJECT_ID);
        return a;
    }

    /** Builds a flattened stepDef (the orchestrator flattens `with` into the stepDef before execute). */
    private StepExecutionContext context(Map<String, Object> withBlock, RuntimeContext runtimeContext) {
        Map<String, Object> stepDef = new LinkedHashMap<>();
        stepDef.put("id", "analyze");
        stepDef.put("uses", "agent");
        stepDef.put("with", withBlock);
        return new StepExecutionContext(new WorkflowRun(), new WorkflowJobRun(),
                stepDef, runtimeContext, PROJECT_ID);
    }

    private RuntimeContext emptyContext() {
        return new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
    }

    @Test
    void getStepTypeReturnsAgent() {
        assertThat(executor.getStepType()).isEqualTo("agent");
    }

    @Test
    void successfulRunMapsTextAndStructuredFieldsToOutputs() {
        when(agentRepository.findByProjectIdAndSlug(PROJECT_ID, "marketing-agent"))
                .thenReturn(Optional.of(agent("agent-1", "marketing-agent")));
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("report", "SEO is healthy");
        structured.put("action_items", java.util.List.of("Fix titles", "Add alt text"));
        when(agentExecutionService.run(any(AgentRunRequest.class))).thenReturn(
                new AgentRunResult("run-9", "Final answer text", structured,
                        new TokenUsage(100, 50), "SUCCEEDED"));

        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "marketing-agent");
        with.put("task", "Analyze SEO health");
        StepResult result = executor.execute(context(with, emptyContext()));

        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(result.getOutputs().get("text")).isEqualTo("Final answer text");
        assertThat(result.getOutputs().get("report")).isEqualTo("SEO is healthy");
        assertThat(result.getOutputs().get("action_items")).contains("Fix titles");
        assertThat(result.getOutputs()).containsKey("data");
    }

    @Test
    void missingAgentYieldsFailed() {
        when(agentRepository.findByProjectIdAndSlug(PROJECT_ID, "ghost"))
                .thenReturn(Optional.empty());
        when(agentRepository.findById("ghost")).thenReturn(Optional.empty());

        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "ghost");
        with.put("task", "Do something");
        StepResult result = executor.execute(context(with, emptyContext()));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("Agent not found: ghost");
    }

    @Test
    void interpolatesTaskAndContextRefsFromUpstreamOutputs() {
        when(agentRepository.findByProjectIdAndSlug(PROJECT_ID, "marketing-agent"))
                .thenReturn(Optional.of(agent("agent-1", "marketing-agent")));
        when(agentExecutionService.run(any(AgentRunRequest.class))).thenReturn(
                new AgentRunResult("run-1", "ok", null, TokenUsage.ZERO, "SUCCEEDED"));

        // steps.collect.outputs.summary -> "42 clicks"; needs.collect.outputs.data -> JSON string
        RuntimeContext ctx = new RuntimeContext(
                Map.of(), Map.of(),
                Map.of("collect", Map.of("summary", "42 clicks")),
                Map.of("collect", Map.of("data", "{\"pageviews\":99}")));

        Map<String, Object> withContext = new LinkedHashMap<>();
        withContext.put("gsc", "${{ needs.collect.outputs.data }}");
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "marketing-agent");
        with.put("task", "Summarize: ${{ steps.collect.outputs.summary }}");
        with.put("context", withContext);

        StepResult result = executor.execute(context(with, ctx));
        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        org.mockito.Mockito.verify(agentExecutionService).run(captor.capture());
        AgentRunRequest req = captor.getValue();
        assertThat(req.task()).isEqualTo("Summarize: 42 clicks");
        assertThat(req.context().get("gsc")).isEqualTo("{\"pageviews\":99}");
        assertThat(req.agentId()).isEqualTo("agent-1");
    }

    @Test
    void declaredOutputsDotPathExtractsStructuredFields() {
        when(agentRepository.findByProjectIdAndSlug(PROJECT_ID, "marketing-agent"))
                .thenReturn(Optional.of(agent("agent-1", "marketing-agent")));
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("report", "All good");
        when(agentExecutionService.run(any(AgentRunRequest.class))).thenReturn(
                new AgentRunResult("run-2", "final", structured, TokenUsage.ZERO, "SUCCEEDED"));

        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "marketing-agent");
        with.put("task", "Analyze");
        // The orchestrator leaves `outputs` at the top level of the stepDef alongside `with`.
        StepExecutionContext base = context(with, emptyContext());
        base.getStepDefinition().put("outputs", Map.of("summary", "body.report"));

        StepResult result = executor.execute(base);
        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(result.getOutputs().get("summary")).isEqualTo("All good");
    }

    @Test
    void missingTaskYieldsFailed() {
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "marketing-agent");
        StepResult result = executor.execute(context(with, emptyContext()));
        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("task");
    }
}
