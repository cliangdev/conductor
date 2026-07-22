package com.conductor.workflow;

import com.conductor.agent.run.AgentExecutionService;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
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
import static org.mockito.Mockito.when;

/**
 * {@link AgentStepExecutor} is now pure parsing/routing — with-block validation errors, definition
 * resolution, runtime resolution, and delegation to whichever {@link AgentStepRuntime} the resolver
 * picked. Output-mapping/status behavior for the {@code api} runtime lives in
 * {@link ApiAgentStepRuntimeTest}; this test never asserts on output content, only on what gets built
 * and where it's routed.
 */
@ExtendWith(MockitoExtension.class)
class AgentStepExecutorTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock
    private AgentExecutionService agentExecutionService;
    @Mock
    private AgentRuntimeResolver runtimeResolver;

    /** Captures the last call it was given and returns a canned result — a plain test double since
     *  {@link AgentStepRuntime} is the SPI under test's routing, not something to mock away entirely. */
    private static final class RecordingRuntime implements AgentStepRuntime {
        private final String id;
        AgentStepCall lastCall;
        StepResult toReturn = StepResult.success("ok", Map.of());

        RecordingRuntime(String id) { this.id = id; }

        @Override
        public String id() { return id; }

        @Override
        public StepResult run(StepExecutionContext context, AgentStepCall call) {
            this.lastCall = call;
            return toReturn;
        }
    }

    private RecordingRuntime apiRuntime;
    private RecordingRuntime claudeCodeRuntime;
    private AgentStepExecutor executor;

    @BeforeEach
    void setUp() {
        apiRuntime = new RecordingRuntime("api");
        claudeCodeRuntime = new RecordingRuntime("claude-code");
        executor = new AgentStepExecutor(agentExecutionService, runtimeResolver, new WorkflowInterpolator(),
                List.of(apiRuntime, claudeCodeRuntime));
    }

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

    private AgentExecutionService.AgentDefinition definition() {
        return new AgentExecutionService.AgentDefinition(
                "agent-1", "marketing-agent", "claude", null, "sys prompt", List.of(), 8, null);
    }

    @Test
    void getStepTypeReturnsAgent() {
        assertThat(executor.getStepType()).isEqualTo("agent");
    }

    @Test
    void missingWithBlockYieldsFailed() {
        StepResult result = executor.execute(new StepExecutionContext(new WorkflowRun(), new WorkflowJobRun(),
                Map.of("id", "analyze", "uses", "agent"), emptyContext(), PROJECT_ID));
        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("with");
    }

    @Test
    void missingAgentYieldsFailed() {
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("task", "Do something");
        StepResult result = executor.execute(context(with, emptyContext()));
        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("with.agent");
    }

    @Test
    void missingTaskYieldsFailed() {
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "marketing-agent");
        StepResult result = executor.execute(context(with, emptyContext()));
        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("task");
    }

    @Test
    void unresolvableAgentYieldsFailed() {
        when(agentExecutionService.resolveDefinition(PROJECT_ID, "ghost"))
                .thenThrow(new EntityNotFoundException("Agent not found: ghost"));

        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "ghost");
        with.put("task", "Do something");
        StepResult result = executor.execute(context(with, emptyContext()));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("Agent not found: ghost");
    }

    @Test
    void unresolvableRuntimeYieldsFailedWithResolverMessage() {
        when(agentExecutionService.resolveDefinition(PROJECT_ID, "marketing-agent")).thenReturn(definition());
        when(runtimeResolver.resolve(PROJECT_ID, definition()))
                .thenThrow(new AgentRuntimeUnresolvedException("no runtime available for you"));

        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "marketing-agent");
        with.put("task", "Do something");
        StepResult result = executor.execute(context(with, emptyContext()));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).isEqualTo("no runtime available for you");
    }

    @Test
    void unknownResolvedRuntimeYieldsFailed() {
        when(agentExecutionService.resolveDefinition(PROJECT_ID, "marketing-agent")).thenReturn(definition());
        when(runtimeResolver.resolve(PROJECT_ID, definition())).thenReturn("some-other-runtime");

        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "marketing-agent");
        with.put("task", "Do something");
        StepResult result = executor.execute(context(with, emptyContext()));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("Unknown agent runtime: some-other-runtime");
    }

    @Test
    void interpolatedAgentRefResolvesFromEventPayload() {
        when(agentExecutionService.resolveDefinition(PROJECT_ID, "knowledge-engineering")).thenReturn(definition());
        when(runtimeResolver.resolve(PROJECT_ID, definition())).thenReturn("claude-code");

        RuntimeContext ctx = new RuntimeContext(Map.of("agentSlug", "knowledge-engineering"), Map.of(), Map.of(), Map.of());
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "${{ event.agentSlug }}");
        with.put("task", "File this batch");

        StepResult result = executor.execute(context(with, ctx));

        assertThat(result).isSameAs(claudeCodeRuntime.toReturn);
        assertThat(claudeCodeRuntime.lastCall).isNotNull();
    }

    @Test
    void agentRefInterpolatingToEmptyYieldsFailed() {
        RuntimeContext ctx = emptyContext(); // no "agentSlug" key in the event payload

        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "${{ event.agentSlug }}");
        with.put("task", "File this batch");

        StepResult result = executor.execute(context(with, ctx));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("with.agent").contains("${{ event.agentSlug }}");
        assertThat(apiRuntime.lastCall).isNull();
        assertThat(claudeCodeRuntime.lastCall).isNull();
    }

    @Test
    void literalAgentRefPassesThroughUnchanged() {
        when(agentExecutionService.resolveDefinition(PROJECT_ID, "marketing-agent")).thenReturn(definition());
        when(runtimeResolver.resolve(PROJECT_ID, definition())).thenReturn("claude-code");

        Map<String, Object> with = new LinkedHashMap<>();
        with.put("agent", "marketing-agent"); // no ${{ }} pattern -- must resolve unchanged
        with.put("task", "Do something");

        StepResult result = executor.execute(context(with, emptyContext()));

        assertThat(result).isSameAs(claudeCodeRuntime.toReturn);
    }

    @Test
    void delegatesToResolverSelectedRuntimeWithInterpolatedCall() {
        when(agentExecutionService.resolveDefinition(PROJECT_ID, "marketing-agent")).thenReturn(definition());
        when(runtimeResolver.resolve(PROJECT_ID, definition())).thenReturn("claude-code");

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
        with.put("timeout_minutes", 15);

        StepResult result = executor.execute(context(with, ctx));

        assertThat(result).isSameAs(claudeCodeRuntime.toReturn);
        assertThat(apiRuntime.lastCall).isNull();
        assertThat(claudeCodeRuntime.lastCall).isNotNull();
        assertThat(claudeCodeRuntime.lastCall.agent()).isEqualTo(definition());
        assertThat(claudeCodeRuntime.lastCall.task()).isEqualTo("Summarize: 42 clicks");
        assertThat(claudeCodeRuntime.lastCall.agentContext().get("gsc")).isEqualTo("{\"pageviews\":99}");
        assertThat(claudeCodeRuntime.lastCall.timeoutMinutes()).isEqualTo(15);
    }
}
