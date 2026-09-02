package com.conductor.workflow;

import com.conductor.entity.Connection;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.conductor.integration.ActionResult;
import com.conductor.integration.AuthType;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.ActiveConnectionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the optional {@code with.connection_id} seam: an action step may target one specific
 * connection when a project holds several ACTIVE connections for the same connector (e.g. two
 * Instagram accounts), while steps that omit it keep today's connector-only resolution.
 */
@ExtendWith(MockitoExtension.class)
class ActionStepExecutorConnectionIdTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String CONNECTOR_ID = "instagram";

    @Mock private ConnectionRepository connectionRepository;
    @Mock private ActionInvocationService actionInvocationService;

    private ActionStepExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ActionStepExecutor(new ActiveConnectionResolver(connectionRepository), actionInvocationService,
                new WorkflowInterpolator(), new ObjectMapper());
    }

    private Connection connection(String id, String authType, String projectId) {
        Connection c = new Connection();
        c.setId(id);
        c.setProjectId(projectId);
        c.setConnectorId(CONNECTOR_ID);
        c.setAuthType(authType);
        c.setStatus("ACTIVE");
        return c;
    }

    private StepExecutionContext context(Map<String, Object> withBlock) {
        Map<String, Object> stepDef = new LinkedHashMap<>();
        stepDef.put("id", "publish");
        stepDef.put("uses", "action");
        stepDef.put("with", withBlock);
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId("run-1");
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        return new StepExecutionContext(new WorkflowRun(), jobRun, stepDef, ctx, PROJECT_ID);
    }

    private Map<String, Object> withBlock(String connectionId) {
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("connector", CONNECTOR_ID);
        with.put("action", "publish_post");
        with.put("input", Map.of("caption", "hi"));
        if (connectionId != null) {
            with.put("connection_id", connectionId);
        }
        return with;
    }

    @Test
    void connectionId_selectsThatConnection_amongSeveralActiveOnesForTheConnector() {
        Connection second = connection("conn-second", AuthType.OAUTH2.name(), PROJECT_ID);
        when(connectionRepository.findById("conn-second")).thenReturn(Optional.of(second));
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.ok(Map.of("post_id", "p1")));

        StepResult result = executor.execute(context(withBlock("conn-second")));

        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
        ArgumentCaptor<Connection> used = ArgumentCaptor.forClass(Connection.class);
        verify(actionInvocationService).invoke(used.capture(), anyString(), any(), anyString(), any());
        assertThat(used.getValue().getId()).isEqualTo("conn-second");
    }

    @Test
    void noConnectionId_resolvesByConnectorAsBefore_preferringPat() {
        Connection oauth = connection("conn-oauth", AuthType.OAUTH2.name(), PROJECT_ID);
        Connection pat = connection("conn-pat", AuthType.PAT.name(), PROJECT_ID);
        when(connectionRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(List.of(oauth, pat));
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.ok(Map.of("post_id", "p1")));

        StepResult result = executor.execute(context(withBlock(null)));

        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
        ArgumentCaptor<Connection> used = ArgumentCaptor.forClass(Connection.class);
        verify(actionInvocationService).invoke(used.capture(), anyString(), any(), anyString(), any());
        assertThat(used.getValue().getId()).isEqualTo("conn-pat");
    }

    @Test
    void blankConnectionId_fallsBackToConnectorOnlyResolution() {
        Connection pat = connection("conn-pat", AuthType.PAT.name(), PROJECT_ID);
        when(connectionRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(List.of(pat));
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.ok(Map.of()));

        StepResult result = executor.execute(context(withBlock("   ")));

        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
    }

    @Test
    void connectionIdOwnedByAnotherProject_failsWithoutLeakingThatItExists() {
        Connection foreign = connection("conn-foreign", AuthType.OAUTH2.name(), "proj-2");
        when(connectionRepository.findById("conn-foreign")).thenReturn(Optional.of(foreign));

        StepResult result = executor.execute(context(withBlock("conn-foreign")));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).isEqualTo("Integration connection not available: " + CONNECTOR_ID);
        assertThat(result.getErrorReason()).doesNotContain("proj-2");
        verifyNoInteractions(actionInvocationService);
    }

    @Test
    void unknownConnectionId_failsWithSameNonLeakingError() {
        when(connectionRepository.findById("conn-missing")).thenReturn(Optional.empty());

        StepResult result = executor.execute(context(withBlock("conn-missing")));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).isEqualTo("Integration connection not available: " + CONNECTOR_ID);
        verifyNoInteractions(actionInvocationService);
    }

    @Test
    void connectionIdForADifferentConnector_fails() {
        Connection otherConnector = connection("conn-other", AuthType.PAT.name(), PROJECT_ID);
        otherConnector.setConnectorId("discord");
        when(connectionRepository.findById("conn-other")).thenReturn(Optional.of(otherConnector));

        StepResult result = executor.execute(context(withBlock("conn-other")));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).isEqualTo("Integration connection not available: " + CONNECTOR_ID);
        verifyNoInteractions(actionInvocationService);
    }

    @Test
    void connectionId_neverFallsBackToConnectorOnlyResolution_whenItDoesNotResolve() {
        when(connectionRepository.findById("conn-missing")).thenReturn(Optional.empty());

        StepResult result = executor.execute(context(withBlock("conn-missing")));

        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        verify(connectionRepository, org.mockito.Mockito.never())
                .findByProjectIdAndConnectorId(anyString(), anyString());
    }
}
