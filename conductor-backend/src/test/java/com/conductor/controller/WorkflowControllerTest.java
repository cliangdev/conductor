package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
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
import com.conductor.workflow.WorkflowJobOrchestrator;
import com.conductor.workflow.WorkflowTriggerService;
import com.conductor.workflow.model.WorkflowYamlParser;
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
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

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

    // Security-filter collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;

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
}
