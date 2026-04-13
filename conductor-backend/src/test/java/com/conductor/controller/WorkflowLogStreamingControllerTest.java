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
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.RunTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.mockito.ArgumentMatchers;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowLogStreamingController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class WorkflowLogStreamingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkflowRunRepository runRepository;

    @MockitoBean
    private WorkflowJobRunRepository jobRunRepository;

    @MockitoBean
    private WorkflowStepRunRepository stepRunRepository;

    @MockitoBean
    private ProjectSecurityService projectSecurityService;

    @MockitoBean
    private RunTokenService runTokenService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    @MockitoBean
    private UserApiKeyRepository userApiKeyRepository;

    private User testUser;
    private WorkflowRun testRun;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId("user-id-123");
        testUser.setEmail("user@example.com");

        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-id-123");
        when(userRepository.findById("user-id-123")).thenReturn(Optional.of(testUser));

        Project project = new Project();
        project.setId("proj-1");

        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setId("wf-1");
        workflow.setProject(project);

        testRun = new WorkflowRun();
        testRun.setId("run-1");
        testRun.setWorkflow(workflow);
        testRun.setStatus(WorkflowRunStatus.SUCCESS);
    }

    @Test
    void sseStreamRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/workflow-runs/run-1/logs/stream"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sseStreamReturns403ForNonMember() throws Exception {
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(testRun));
        when(projectSecurityService.isProjectMember("proj-1", "user-id-123")).thenReturn(false);

        mockMvc.perform(get("/api/v1/workflow-runs/run-1/logs/stream")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sseStreamDoesNotReturn403Or404ForProjectMember() throws Exception {
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(testRun));
        when(projectSecurityService.isProjectMember("proj-1", "user-id-123")).thenReturn(true);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(Collections.emptyList());
        when(objectMapper.writeValueAsString(ArgumentMatchers.any())).thenReturn("{}");

        var result = mockMvc.perform(get("/api/v1/workflow-runs/run-1/logs/stream")
                        .header("Authorization", "Bearer valid-token"))
                .andReturn();

        int status = result.getResponse().getStatus();
        assert status == 200 || status == 0 : "Expected 200 or async 0, got " + status;
    }

    @Test
    void logChunkReturns401ForMissingToken() throws Exception {
        mockMvc.perform(post("/internal/workflow-runs/run-1/log-chunk")
                        .contentType("application/json")
                        .content("{\"lines\":[\"hello\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logChunkReturns401ForInvalidToken() throws Exception {
        when(runTokenService.validateRunToken("bad-token", "run-1")).thenReturn(false);

        mockMvc.perform(post("/internal/workflow-runs/run-1/log-chunk")
                        .header("Authorization", "Bearer bad-token")
                        .contentType("application/json")
                        .content("{\"lines\":[\"hello\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logChunkReturns200ForValidToken() throws Exception {
        when(runTokenService.validateRunToken("valid-run-token", "run-1")).thenReturn(true);

        mockMvc.perform(post("/internal/workflow-runs/run-1/log-chunk")
                        .header("Authorization", "Bearer valid-run-token")
                        .contentType("application/json")
                        .content("{\"workerJobId\":\"job-1\",\"lines\":[\"log line 1\"],\"timestamp\":\"2026-04-12T00:00:00Z\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void outputsCallbackReturns401ForInvalidToken() throws Exception {
        when(runTokenService.validateRunToken("bad-token", "run-1")).thenReturn(false);

        mockMvc.perform(post("/internal/workflow-runs/run-1/outputs")
                        .header("Authorization", "Bearer bad-token")
                        .contentType("application/json")
                        .content("{\"workerJobId\":\"job-1\",\"outputs\":{\"key\":\"value\"}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jobFailedCallbackReturns401ForInvalidToken() throws Exception {
        when(runTokenService.validateRunToken("bad-token", "run-1")).thenReturn(false);

        mockMvc.perform(post("/internal/workflow-runs/run-1/job-failed")
                        .header("Authorization", "Bearer bad-token")
                        .contentType("application/json")
                        .content("{\"jobId\":\"job-1\",\"reason\":\"OOM\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jobFailedCallbackReturns200ForValidToken() throws Exception {
        when(runTokenService.validateRunToken("valid-run-token", "run-1")).thenReturn(true);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(Collections.emptyList());

        mockMvc.perform(post("/internal/workflow-runs/run-1/job-failed")
                        .header("Authorization", "Bearer valid-run-token")
                        .contentType("application/json")
                        .content("{\"jobId\":\"job-1\",\"reason\":\"Container exited with code 1\"}"))
                .andExpect(status().isOk());
    }
}
