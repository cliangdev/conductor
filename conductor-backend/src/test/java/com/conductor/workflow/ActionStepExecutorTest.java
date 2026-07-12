package com.conductor.workflow;

import com.conductor.entity.Connection;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.conductor.integration.ActionResult;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ActionInvocationService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionStepExecutorTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private ConnectionRepository connectionRepository;
    @Mock private ActionInvocationService actionInvocationService;

    private ActionStepExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ActionStepExecutor(connectionRepository, actionInvocationService,
                new WorkflowInterpolator(), new ObjectMapper());
    }

    private StepExecutionContext context(String stepId, Map<String, Object> withBlock, RuntimeContext runtimeContext) {
        Map<String, Object> stepDef = new LinkedHashMap<>();
        stepDef.put("id", stepId);
        stepDef.put("uses", "action");
        stepDef.put("with", withBlock);
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId("run-1");
        return new StepExecutionContext(new WorkflowRun(), jobRun, stepDef, runtimeContext, PROJECT_ID);
    }

    private RuntimeContext emptyContext() {
        return new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
    }

    private Connection activeConnection() {
        Connection c = new Connection();
        c.setId("conn-1");
        c.setProjectId(PROJECT_ID);
        c.setConnectorId("discord");
        c.setStatus("ACTIVE");
        return c;
    }

    @Test
    void getStepTypeReturnsAction() {
        assertThat(executor.getStepType()).isEqualTo("action");
    }

    @Test
    void missingConnector_failsWithoutTouchingInvocationService() {
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("action", "post_message");
        StepResult result = executor.execute(context("post", with, emptyContext()));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("with.connector");
    }

    @Test
    void missingAction_fails() {
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("connector", "discord");
        StepResult result = executor.execute(context("post", with, emptyContext()));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("with.action");
    }

    @Test
    void noActiveConnection_fails() {
        when(connectionRepository.findByProjectIdAndConnectorId(PROJECT_ID, "discord")).thenReturn(List.of());

        Map<String, Object> with = new LinkedHashMap<>();
        with.put("connector", "discord");
        with.put("action", "post_message");
        StepResult result = executor.execute(context("post", with, emptyContext()));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("Integration not connected");
    }

    @Test
    void missingJobRunId_failsLoudly_insteadOfMintingCollidingIdempotencyKey() {
        when(connectionRepository.findByProjectIdAndConnectorId(PROJECT_ID, "discord"))
                .thenReturn(List.of(activeConnection()));

        Map<String, Object> with = new LinkedHashMap<>();
        with.put("connector", "discord");
        with.put("action", "post_message");
        with.put("input", Map.of("content", "hi"));

        Map<String, Object> stepDef = new LinkedHashMap<>();
        stepDef.put("id", "post");
        stepDef.put("uses", "action");
        stepDef.put("with", with);
        StepExecutionContext contextWithNoJobRun =
                new StepExecutionContext(new WorkflowRun(), null, stepDef, emptyContext(), PROJECT_ID);

        StepResult result = executor.execute(contextWithNoJobRun);

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("job run id");
        org.mockito.Mockito.verifyNoInteractions(actionInvocationService);
    }

    @Test
    void successfulInvoke_interpolatesInput_andMapsOutputsAndIdempotencyKey() {
        when(connectionRepository.findByProjectIdAndConnectorId(PROJECT_ID, "discord"))
                .thenReturn(List.of(activeConnection()));

        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(),
                Map.of(), Map.of("collect", Map.of("summary", "all good")));

        when(actionInvocationService.invoke(any(), eq("post_message"), any(), eq("wfstep:run-1:post"), any()))
                .thenReturn(ActionResult.ok(Map.of("message_id", "m1", "channel_id", "c1")));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("content", "Result: ${{ needs.collect.outputs.summary }}");
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("connector", "discord");
        with.put("action", "post_message");
        with.put("input", input);

        StepResult result = executor.execute(context("post", with, ctx));

        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(result.getOutputs()).containsEntry("message_id", "m1");
        assertThat(result.getOutputs()).containsEntry("channel_id", "c1");

        verify(actionInvocationService).invoke(any(), eq("post_message"), argThatContainsInterpolatedContent(), eq("wfstep:run-1:post"), any());
    }

    @Test
    void failedInvoke_mapsToStepFailure() {
        when(connectionRepository.findByProjectIdAndConnectorId(PROJECT_ID, "discord"))
                .thenReturn(List.of(activeConnection()));
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.error("Discord webhook rejected request: 400"));

        Map<String, Object> with = new LinkedHashMap<>();
        with.put("connector", "discord");
        with.put("action", "post_message");
        with.put("input", Map.of("content", "hi"));
        StepResult result = executor.execute(context("post", with, emptyContext()));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).isEqualTo("Discord webhook rejected request: 400");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argThatContainsInterpolatedContent() {
        return org.mockito.ArgumentMatchers.argThat(m ->
                m instanceof Map && "Result: all good".equals(((Map<String, Object>) m).get("content")));
    }
}
