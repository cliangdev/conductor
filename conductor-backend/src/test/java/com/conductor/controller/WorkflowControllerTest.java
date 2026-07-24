package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.generated.model.UpdateWorkflowRunStatusRequest;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.workflow.RunTokenService;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowScheduleRepository;
import com.conductor.repository.WorkflowScheduleSkipRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.WorkflowDefinitionLifecycleService;
import com.conductor.service.WorkflowService;
import com.conductor.service.WorkflowViewService;
import com.conductor.workflow.StepExecutionContext;
import com.conductor.workflow.StepResult;
import com.conductor.workflow.WorkflowExecutionBackend;
import com.conductor.workflow.WorkflowFailureCircuitBreaker;
import com.conductor.workflow.WorkflowJobOrchestrator;
import com.conductor.workflow.WorkflowTriggerService;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.conductor.workflow.schema.StepSchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the COND-22 lifecycle/automation discrimination and sidebar API on {@link WorkflowController}:
 * the explicit {@code kind} field, the {@code definition:{}} leak fix, the {@code lifecycle}/{@code sidebar}
 * list filters, and {@code PATCH .../sidebar}.
 */
@WebMvcTest(WorkflowController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class WorkflowControllerTest {

    @TestConfiguration
    static class ObjectMapperConfig {
        // The @WebMvcTest slice does not expose an injectable ObjectMapper for the controller's
        // constructor; provide the real one used by toDto's definition mapping.
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        // No dependencies of its own; a real instance is simpler than mocking parse() per test.
        @Bean
        WorkflowYamlParser workflowYamlParser() {
            return new WorkflowYamlParser();
        }

        // Hand-authored/static, like WorkflowYamlParser above -- a real instance is simpler than
        // mocking it (none of these tests exercise the step-schema endpoint's content). Its
        // @PostConstruct cross-checks the given backend types against its own step-type keys, so this
        // slice (which doesn't wire real WorkflowExecutionBackend beans) hands it fakes matching what
        // production actually registers.
        @Bean
        StepSchemaRegistry stepSchemaRegistry() {
            List<WorkflowExecutionBackend> backends = new java.util.ArrayList<>();
            for (String type : new String[] {"http", "docker", "kestra", "integration", "agent", "claude-code", "action"}) {
                backends.add(new WorkflowExecutionBackend() {
                    @Override
                    public String getStepType() {
                        return type;
                    }

                    @Override
                    public StepResult execute(StepExecutionContext context) {
                        throw new UnsupportedOperationException("not exercised by this test");
                    }
                });
            }
            return new StepSchemaRegistry(backends);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WorkflowController controller;

    @MockitoBean private WorkflowService workflowService;
    @MockitoBean private WorkflowTriggerService workflowTriggerService;
    @MockitoBean private WorkflowJobOrchestrator workflowJobOrchestrator;
    @MockitoBean private ProjectSecurityService projectSecurityService;
    @MockitoBean private WorkflowDefinitionRepository workflowRepository;
    @MockitoBean private WorkflowRunRepository runRepository;
    @MockitoBean private WorkflowJobRunRepository jobRunRepository;
    @MockitoBean private WorkflowStepRunRepository stepRunRepository;
    @MockitoBean private WorkflowScheduleRepository scheduleRepository;
    @MockitoBean private WorkflowScheduleSkipRepository scheduleSkipRepository;
    @MockitoBean private WorkflowDefinitionLifecycleService lifecycleService;
    @MockitoBean private WorkflowViewService workflowViewService;
    @MockitoBean private WorkflowFailureCircuitBreaker circuitBreaker;

    // Security-filter collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

    private static final String PROJECT_ID = "proj-1";

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId("user-1");
        user.setEmail("user@example.com");
        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
    }

    private WorkflowDefinition lifecycleWorkflow() {
        WorkflowDefinition def = baseWorkflow("wf-life", "ENGINEERING");
        try {
            def.setDefinition(objectMapper.readTree("{\"id\":\"ENGINEERING\",\"noun\":\"Issue\",\"statuses\":[{\"id\":\"DRAFT\"}]}"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        def.setSchemaVersion(1);
        def.setState("PUBLISHED");
        def.setArea("ENGINEERING");
        def.setSidebarEnabled(true);
        return def;
    }

    private WorkflowDefinition automationWorkflow() {
        WorkflowDefinition def = baseWorkflow("wf-auto", "my-workflow");
        def.setYaml("on:\n  workflow_dispatch: {}\n");
        def.setDefinition(null); // YAML automation — no statechart
        def.setSidebarEnabled(false);
        return def;
    }

    private WorkflowDefinition baseWorkflow(String id, String name) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId(id);
        def.setProject(project);
        def.setName(name);
        def.setEnabled(true);
        def.setVersion(1);
        def.setCreatedAt(OffsetDateTime.now());
        def.setUpdatedAt(OffsetDateTime.now());
        return def;
    }

    @Test
    void listWorkflowsMapsKindSlugNounAndNullsDefinitionForAutomation() throws Exception {
        when(workflowService.listWorkflows(PROJECT_ID, null, null, null))
                .thenReturn(List.of(lifecycleWorkflow(), automationWorkflow()));

        mockMvc.perform(get("/api/v1/projects/{p}/workflows", PROJECT_ID)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("LIFECYCLE"))
                .andExpect(jsonPath("$[0].slug").value("ENGINEERING"))
                .andExpect(jsonPath("$[0].noun").value("Issue"))
                .andExpect(jsonPath("$[0].definition").isNotEmpty())
                // The regression: an automation must report AUTOMATION + null definition, never {}.
                .andExpect(jsonPath("$[1].kind").value("AUTOMATION"))
                .andExpect(jsonPath("$[1].definition").doesNotExist())
                .andExpect(jsonPath("$[1].slug").doesNotExist())
                .andExpect(jsonPath("$[1].noun").doesNotExist());
    }

    @Test
    void listWorkflowsDelegatesFiltersToService() throws Exception {
        // Filtering lives in the service now; the controller must pass the query params straight through.
        when(workflowService.listWorkflows(PROJECT_ID, true, "PUBLISHED", true))
                .thenReturn(List.of(lifecycleWorkflow()));

        mockMvc.perform(get("/api/v1/projects/{p}/workflows", PROJECT_ID)
                        .param("lifecycle", "true")
                        .param("state", "PUBLISHED")
                        .param("sidebar", "true")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].slug").value("ENGINEERING"));

        verify(workflowService).listWorkflows(PROJECT_ID, true, "PUBLISHED", true);
    }

    @Test
    void getWorkflowStepSchema_returnsRegistryContentToProjectMembers() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/step-schema", PROJECT_ID)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepTypes.length()").value(8))
                .andExpect(jsonPath("$.stepTypes[?(@.type=='claude-code')]").isNotEmpty())
                .andExpect(jsonPath("$.interpolation.roots[?(@.name=='event')]").isNotEmpty())
                .andExpect(jsonPath("$.interpolation.functions[?(@.name=='always()')]").isNotEmpty());
    }

    @Test
    void getWorkflowStepSchema_rejectsNonMember() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/step-schema", PROJECT_ID)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void setWorkflowSidebarTogglesVisibility() throws Exception {
        WorkflowDefinition updated = lifecycleWorkflow();
        updated.setSidebarEnabled(false);
        when(workflowService.setSidebarEnabled(eq(PROJECT_ID), eq("wf-life"), anyString(), eq(false)))
                .thenReturn(updated);

        mockMvc.perform(patch("/api/v1/projects/{p}/workflows/{w}/sidebar", PROJECT_ID, "wf-life")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sidebarEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sidebarEnabled").value(false))
                .andExpect(jsonPath("$.kind").value("LIFECYCLE"));
    }

    private WorkflowRun runWithEventPayload(String eventPayloadJson) {
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(automationWorkflow());
        run.setTriggerType("workflow_dispatch");
        run.setStatus(WorkflowRunStatus.PENDING);
        run.setStartedAt(OffsetDateTime.now());
        run.setEventPayload(eventPayloadJson);
        return run;
    }

    @Test
    void dispatchWorkflow_withInputs_passesInputsAndReturnsEventPayload() throws Exception {
        when(workflowService.getWorkflow(PROJECT_ID, "wf-auto")).thenReturn(automationWorkflow());
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(workflowTriggerService.triggerManual(any(), eq("user-1"), eq(java.util.Map.of("environment", "staging"))))
                .thenReturn(runWithEventPayload(
                        "{\"type\":\"workflow_dispatch\",\"inputs\":{\"environment\":\"staging\"}}"));

        mockMvc.perform(post("/api/v1/projects/{p}/workflows/{w}/dispatch", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputs\":{\"environment\":\"staging\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.eventPayload.inputs.environment").value("staging"));

        verify(workflowTriggerService).triggerManual(any(), eq("user-1"), eq(java.util.Map.of("environment", "staging")));
    }

    @Test
    void dispatchWorkflow_withoutBody_stillWorks() throws Exception {
        when(workflowService.getWorkflow(PROJECT_ID, "wf-auto")).thenReturn(automationWorkflow());
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(workflowTriggerService.triggerManual(any(), eq("user-1"), isNull()))
                .thenReturn(runWithEventPayload("{\"type\":\"workflow_dispatch\"}"));

        mockMvc.perform(post("/api/v1/projects/{p}/workflows/{w}/dispatch", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("run-1"));

        verify(workflowTriggerService).triggerManual(any(), eq("user-1"), isNull());
    }

    @Test
    void dispatchWorkflow_rejectsWhenAutoPaused_withPauseSpecificMessage() throws Exception {
        // Mirrors what WorkflowFailureCircuitBreaker leaves behind on the workflow row after tripping.
        WorkflowDefinition workflow = automationWorkflow();
        workflow.setEnabled(false);
        workflow.setConsecutiveFailures(5);
        workflow.setAutoPausedAt(OffsetDateTime.now());
        workflow.setAutoPauseReason("CONSECUTIVE_FAILURES");
        workflow.setAutoPausedRunId("run-that-tripped-it");
        when(workflowService.getWorkflow(PROJECT_ID, "wf-auto")).thenReturn(workflow);
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);

        mockMvc.perform(post("/api/v1/projects/{p}/workflows/{w}/dispatch", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "'my-workflow' is disabled — it was auto-paused after 5 consecutive failed runs. "
                                + "Re-enable it to clear the pause and try again."));

        verify(workflowTriggerService, org.mockito.Mockito.never()).triggerManual(any(), anyString(), any());
    }

    @Test
    void dispatchWorkflow_rejectsWhenYamlOptsOutOfManualDispatch() throws Exception {
        // Mirrors knowledge-librarian.yaml: workflow_dispatch declared (so it's a recognized trigger
        // kind for programmatic fireTrigger() calls) but manual: false opts out of this endpoint.
        WorkflowDefinition workflow = automationWorkflow();
        workflow.setName("Knowledge Librarian");
        workflow.setYaml("on:\n  workflow_dispatch:\n    manual: false\n");
        when(workflowService.getWorkflow(PROJECT_ID, "wf-auto")).thenReturn(workflow);
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);

        mockMvc.perform(post("/api/v1/projects/{p}/workflows/{w}/dispatch", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "'Knowledge Librarian' is managed automatically and can't be run manually — "
                                + "its trigger data is supplied by the process that dispatches it."));

        verify(workflowTriggerService, org.mockito.Mockito.never()).triggerManual(any(), anyString(), any());
    }

    @Test
    void updateWorkflowRunStatus_recordsOutcome_whenJobCompletionDidNotFinalizeTheRun() {
        // A run with a second job still RUNNING: completeRemoteJob is dispatched for the AWAITING_PICKUP
        // job, but its internal checkRunCompletion sees the other job non-terminal and does NOT finalize
        // the run — so this method's own status assignment below is the actual, only, finalization, and
        // must be the one to call recordOutcome (regression test for a bug where the "already finalized"
        // guard was inferred from awaitingPickupJobIds being non-empty instead of the run's real status).
        WorkflowDefinition workflow = automationWorkflow();
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(workflow);
        run.setStatus(WorkflowRunStatus.RUNNING);
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(run));

        WorkflowJobRun awaitingJob = new WorkflowJobRun();
        awaitingJob.setJobId("deploy");
        awaitingJob.setStatus(WorkflowJobStatus.AWAITING_PICKUP);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(awaitingJob));

        UpdateWorkflowRunStatusRequest request = new UpdateWorkflowRunStatusRequest();
        request.setStatus("FAILED");

        controller.updateWorkflowRunStatus("run-1", request);

        verify(workflowJobOrchestrator).completeRemoteJob("run-1", "deploy", WorkflowJobStatus.FAILED, null);
        verify(circuitBreaker).recordOutcome(run);
        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.FAILED);
    }

    @Test
    void updateWorkflowRunStatus_skipsRecordOutcome_whenJobCompletionAlreadyFinalizedTheRun() {
        // completeRemoteJob's own checkRunCompletion already finalized (all jobs terminal) and recorded
        // the outcome in its own transaction — simulated here by the re-fetch returning an already-FAILED
        // run. recordOutcome must not fire a second time for the same run.
        WorkflowDefinition workflow = automationWorkflow();
        WorkflowRun alreadyFinalizedRun = new WorkflowRun();
        alreadyFinalizedRun.setId("run-1");
        alreadyFinalizedRun.setWorkflow(workflow);
        alreadyFinalizedRun.setStatus(WorkflowRunStatus.FAILED);
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(alreadyFinalizedRun));

        WorkflowJobRun awaitingJob = new WorkflowJobRun();
        awaitingJob.setJobId("deploy");
        awaitingJob.setStatus(WorkflowJobStatus.AWAITING_PICKUP);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(awaitingJob));

        UpdateWorkflowRunStatusRequest request = new UpdateWorkflowRunStatusRequest();
        request.setStatus("FAILED");

        controller.updateWorkflowRunStatus("run-1", request);

        verify(circuitBreaker, org.mockito.Mockito.never()).recordOutcome(any());
    }
}
