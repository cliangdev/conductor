package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
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
import com.conductor.workflow.WorkflowRunCancellationService;
import com.conductor.workflow.WorkflowRunQueryService;
import com.conductor.workflow.WorkflowTriggerService;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.conductor.workflow.schema.StepSchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WorkflowRunQueryService.class})
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
    @MockitoBean private WorkflowRunCancellationService runCancellationService;

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
    void cancelWorkflowRun_succeeds_forAGenuinelyPendingRun() throws Exception {
        // Regression test for a reported "Resource not found" 404 on a genuinely PENDING run: this
        // exercises the real POST .../cancel route end-to-end (no test previously hit it at all), to
        // catch any future routing regression that would reproduce that report.
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(workflowService.getWorkflow(PROJECT_ID, "wf-auto")).thenReturn(automationWorkflow());
        when(runRepository.findByIdWithWorkflow("run-1"))
                .thenReturn(Optional.of(runWithEventPayload("{\"type\":\"workflow_dispatch\"}")));

        WorkflowRun cancellingRun = runWithEventPayload("{\"type\":\"workflow_dispatch\"}");
        cancellingRun.setStatus(WorkflowRunStatus.CANCELLING);
        when(runCancellationService.cancelRun("run-1")).thenReturn(cancellingRun);

        mockMvc.perform(post("/api/v1/projects/{p}/workflows/{w}/runs/{r}/cancel", PROJECT_ID, "wf-auto", "run-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.status").value("CANCELLING"));

        verify(runCancellationService).cancelRun("run-1");
    }

    @Test
    void getWorkflowRun_stampsExplanationAndRemediationForFailedStep() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(runRepository.findByIdWithWorkflow("run-1"))
                .thenReturn(Optional.of(runWithEventPayload("{\"type\":\"workflow_dispatch\"}")));

        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId("job-1");
        jobRun.setJobId("notify");
        jobRun.setStatus(WorkflowJobStatus.FAILED);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        WorkflowStepRun failedStep = new WorkflowStepRun();
        failedStep.setId("step-1");
        failedStep.setStepId("notify");
        failedStep.setStepName("notify");
        failedStep.setStepType("action");
        failedStep.setStatus(WorkflowStepStatus.FAILED);
        failedStep.setErrorReason("CLAUDE_TIMEOUT");
        when(stepRunRepository.findByJobRunId("job-1")).thenReturn(List.of(failedStep));

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs/{r}", PROJECT_ID, "wf-auto", "run-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].steps[0].errorReason").value("CLAUDE_TIMEOUT"))
                .andExpect(jsonPath("$.jobs[0].steps[0].explanation").value("The step exceeded its timeout_minutes."))
                .andExpect(jsonPath("$.jobs[0].steps[0].remediation").value(
                        "Increase timeout_minutes, or reduce the amount of work the step does per run."));
    }

    @Test
    void getWorkflowRun_omitsExplanationAndRemediationForNonFailedStep() throws Exception {
        // Regression: explanation/remediation must only ever be stamped for FAILED steps, per the
        // OpenAPI spec — a successful step's errorReason is unset, so there'd be nothing to explain.
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(runRepository.findByIdWithWorkflow("run-1"))
                .thenReturn(Optional.of(runWithEventPayload("{\"type\":\"workflow_dispatch\"}")));

        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId("job-1");
        jobRun.setJobId("review");
        jobRun.setStatus(WorkflowJobStatus.SUCCESS);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        WorkflowStepRun succeededStep = new WorkflowStepRun();
        succeededStep.setId("step-1");
        succeededStep.setStepId("review");
        succeededStep.setStepName("review");
        succeededStep.setStepType("agent");
        succeededStep.setStatus(WorkflowStepStatus.SUCCESS);
        when(stepRunRepository.findByJobRunId("job-1")).thenReturn(List.of(succeededStep));

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs/{r}", PROJECT_ID, "wf-auto", "run-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].steps[0].explanation").doesNotExist())
                .andExpect(jsonPath("$.jobs[0].steps[0].remediation").doesNotExist());
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

    private WorkflowRun runWithStatus(String id, WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setWorkflow(automationWorkflow());
        run.setTriggerType("workflow_dispatch");
        run.setStatus(status);
        run.setStartedAt(OffsetDateTime.now());
        return run;
    }

    @Test
    void listWorkflowRuns_filtersByStatus_whenStatusParamGiven() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        WorkflowRun pending = runWithStatus("run-1", WorkflowRunStatus.PENDING);
        Page<WorkflowRun> page = new PageImpl<>(List.of(pending));
        when(runRepository.findByWorkflowIdAndStatusIn(eq("wf-auto"), eq(Set.of(WorkflowRunStatus.PENDING)),
                any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs", PROJECT_ID, "wf-auto")
                        .param("status", "PENDING")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        verify(runRepository, never()).findByWorkflowId(anyString(), any(Pageable.class));
    }

    @Test
    void listWorkflowRuns_returnsRunsInEveryStatus_whenStatusParamOmitted() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        Page<WorkflowRun> page = new PageImpl<>(List.of(
                runWithStatus("run-1", WorkflowRunStatus.PENDING),
                runWithStatus("run-2", WorkflowRunStatus.SUCCESS)));
        when(runRepository.findByWorkflowId(eq("wf-auto"), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        verify(runRepository, never()).findByWorkflowIdAndStatusIn(anyString(), any(), any(Pageable.class));
    }

    @Test
    void listWorkflowRuns_rejectsAnUnrecognizedStatusValueWith400() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs", PROJECT_ID, "wf-auto")
                        .param("status", "NOT_A_REAL_STATUS")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listWorkflowRuns_setsWaitReason_onlyForRunsWithAnUnclaimedAwaitingPickupJob_inOneBatchedQuery() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        WorkflowRun awaitingRunner = runWithStatus("run-1", WorkflowRunStatus.RUNNING);
        WorkflowRun runningNoWait = runWithStatus("run-2", WorkflowRunStatus.PENDING);
        WorkflowRun finished = runWithStatus("run-3", WorkflowRunStatus.SUCCESS);
        Page<WorkflowRun> page = new PageImpl<>(List.of(awaitingRunner, runningNoWait, finished));
        when(runRepository.findByWorkflowId(eq("wf-auto"), any(Pageable.class))).thenReturn(page);
        // Only the two non-terminal runs should ever be looked up — the finished run is excluded before
        // the query even runs.
        when(jobRunRepository.findDistinctRunIdsByRunIdInAndStatusAndClaimedAtIsNull(
                eq(List.of("run-1", "run-2")), eq(WorkflowJobStatus.AWAITING_PICKUP)))
                .thenReturn(List.of("run-1"));

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].waitReason").value("AWAITING_RUNNER"))
                .andExpect(jsonPath("$[1].waitReason").doesNotExist())
                .andExpect(jsonPath("$[2].waitReason").doesNotExist());

        // One call for the whole page, not one per run -- proves this isn't N+1.
        verify(jobRunRepository, times(1))
                .findDistinctRunIdsByRunIdInAndStatusAndClaimedAtIsNull(any(), eq(WorkflowJobStatus.AWAITING_PICKUP));
    }

    @Test
    void listWorkflowRuns_setsWaitReason_null_onceTheAwaitingPickupJobIsClaimed() throws Exception {
        // The regression this whole fix targets: a claimed AWAITING_PICKUP job is actively running on
        // a self-hosted daemon, not waiting for one -- the batched lookup must exclude it.
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        WorkflowRun claimedRunner = runWithStatus("run-1", WorkflowRunStatus.RUNNING);
        Page<WorkflowRun> page = new PageImpl<>(List.of(claimedRunner));
        when(runRepository.findByWorkflowId(eq("wf-auto"), any(Pageable.class))).thenReturn(page);
        when(jobRunRepository.findDistinctRunIdsByRunIdInAndStatusAndClaimedAtIsNull(
                eq(List.of("run-1")), eq(WorkflowJobStatus.AWAITING_PICKUP)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].waitReason").doesNotExist());
    }

    @Test
    void listWorkflowRuns_stateQueued_matchesARunThatsRunningButBlockedOnAnUnclaimedJob() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        WorkflowRun blockedRun = runWithStatus("run-1", WorkflowRunStatus.RUNNING);
        Page<WorkflowRun> page = new PageImpl<>(List.of(blockedRun));
        when(runRepository.findQueuedByWorkflowId(eq("wf-auto"),
                eq(Set.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.PENDING_LOCAL_PICKUP)),
                eq(WorkflowJobStatus.AWAITING_PICKUP), eq(WorkflowRunStatus.TERMINAL_STATUSES), any(Pageable.class)))
                .thenReturn(page);
        when(jobRunRepository.findDistinctRunIdsByRunIdInAndStatusAndClaimedAtIsNull(any(), any()))
                .thenReturn(List.of("run-1"));

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs", PROJECT_ID, "wf-auto")
                        .param("state", "queued")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("run-1"))
                .andExpect(jsonPath("$[0].waitReason").value("AWAITING_RUNNER"));

        verify(runRepository, never()).findByWorkflowId(anyString(), any(Pageable.class));
        verify(runRepository, never()).findRunningByWorkflowId(anyString(), any(), any(), any(Pageable.class));
    }

    @Test
    void listWorkflowRuns_stateRunning_excludesThatSameRun_andIncludesANormallyRunningOne() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        WorkflowRun normallyRunning = runWithStatus("run-2", WorkflowRunStatus.RUNNING);
        Page<WorkflowRun> page = new PageImpl<>(List.of(normallyRunning));
        when(runRepository.findRunningByWorkflowId(eq("wf-auto"),
                eq(Set.of(WorkflowRunStatus.RUNNING, WorkflowRunStatus.CANCELLING)),
                eq(WorkflowJobStatus.AWAITING_PICKUP), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs", PROJECT_ID, "wf-auto")
                        .param("state", "running")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("run-2"));

        verify(runRepository, never()).findQueuedByWorkflowId(anyString(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void listWorkflowRuns_rejectsAnUnrecognizedStateValueWith400() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs", PROJECT_ID, "wf-auto")
                        .param("state", "not-a-real-state")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listWorkflowRuns_rejectsBothStateAndStatusGivenTogetherWith400() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs", PROJECT_ID, "wf-auto")
                        .param("state", "queued")
                        .param("status", "PENDING")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());

        verify(runRepository, never()).findQueuedByWorkflowId(anyString(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void listWorkflowRuns_returns404_whenWorkflowBelongsToAnotherProject() throws Exception {
        // Without this scoping check, any project member could list another project's runs -- and now
        // its queue/wait-reason state -- by guessing a workflowId, the same class of gap
        // cancelWorkflowRun/cancelQueuedWorkflowRuns already guard against.
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(workflowService.getWorkflow(PROJECT_ID, "wf-auto"))
                .thenThrow(new EntityNotFoundException("Workflow not found: wf-auto"));

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/runs", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());

        verify(runRepository, never()).findByWorkflowId(anyString(), any(Pageable.class));
    }

    @Test
    void listScheduleSkips_returns404_whenWorkflowBelongsToAnotherProject() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(workflowService.getWorkflow(PROJECT_ID, "wf-auto"))
                .thenThrow(new EntityNotFoundException("Workflow not found: wf-auto"));

        mockMvc.perform(get("/api/v1/projects/{p}/workflows/{w}/schedule-skips", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());

        verify(scheduleRepository, never()).findByWorkflowId(anyString());
    }

    @Test
    void cancelQueuedWorkflowRuns_returnsTheCancelledCount() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(workflowService.getWorkflow(PROJECT_ID, "wf-auto")).thenReturn(automationWorkflow());
        when(runCancellationService.cancelQueuedRuns("wf-auto")).thenReturn(3);

        mockMvc.perform(post("/api/v1/projects/{p}/workflows/{w}/runs/cancel-queued", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledCount").value(3));

        verify(runCancellationService).cancelQueuedRuns("wf-auto");
    }

    @Test
    void cancelQueuedWorkflowRuns_isANoOp_returningZero_whenNothingIsQueued() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(workflowService.getWorkflow(PROJECT_ID, "wf-auto")).thenReturn(automationWorkflow());
        when(runCancellationService.cancelQueuedRuns("wf-auto")).thenReturn(0);

        mockMvc.perform(post("/api/v1/projects/{p}/workflows/{w}/runs/cancel-queued", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledCount").value(0));
    }

    @Test
    void cancelQueuedWorkflowRuns_returns404_whenWorkflowBelongsToAnotherProject() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(workflowService.getWorkflow(PROJECT_ID, "wf-auto"))
                .thenThrow(new EntityNotFoundException("Workflow not found: wf-auto"));

        mockMvc.perform(post("/api/v1/projects/{p}/workflows/{w}/runs/cancel-queued", PROJECT_ID, "wf-auto")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());

        verify(runCancellationService, never()).cancelQueuedRuns(anyString());
    }
}
