package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.legacy.IssueController;
import com.conductor.exception.BusinessException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.generated.model.IssueResponse;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.generated.model.AvailableTransition;
import com.conductor.generated.model.AvailableTransitionsResponse;
import com.conductor.service.WorkItemService;
import com.conductor.service.JwtService;
import com.conductor.service.WorkItemWorkflowService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IssueController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class IssueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkItemService workItemService;

    @MockitoBean
    private WorkItemWorkflowService workItemWorkflowService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    @MockitoBean
    private UserApiKeyRepository userApiKeyRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId("user-id-123");
        testUser.setEmail("user@example.com");

        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-id-123");
        when(userRepository.findById("user-id-123")).thenReturn(Optional.of(testUser));
    }

    private IssueResponse buildIssueResponse(String id, String type, String status) {
        return new IssueResponse(id, "proj-1", type, "Test Issue", status, "user-id-123",
                OffsetDateTime.now(), OffsetDateTime.now(), 1, "PROJ-1");
    }

    @Test
    void listAvailableTransitionsReturns200() throws Exception {
        AvailableTransition t = new AvailableTransition("IN_REVIEW", "Submit for review");
        t.setRequiresReview(false);
        AvailableTransitionsResponse response =
                new AvailableTransitionsResponse("ENGINEERING", "DRAFT", List.of(t));
        response.setNoun("Issue");
        when(workItemWorkflowService.availableTransitions(eq("proj-1"), eq("issue-1"), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/projects/proj-1/issues/issue-1/available-transitions")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflow").value("ENGINEERING"))
                .andExpect(jsonPath("$.currentStatus").value("DRAFT"))
                .andExpect(jsonPath("$.transitions[0].toStatus").value("IN_REVIEW"));
    }

    @Test
    void createIssueReturns201WithDraftStatus() throws Exception {
        IssueResponse response = buildIssueResponse("issue-1", "PRD", "DRAFT");

        when(workItemService.createIssue(eq("proj-1"), any(), eq(testUser))).thenReturn(response);

        mockMvc.perform(post("/api/v1/projects/proj-1/issues")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"PRD\", \"title\": \"Test Issue\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("issue-1"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.type").value("PRD"));
    }

    @Test
    void listIssuesFiltersByType() throws Exception {
        IssueResponse r1 = buildIssueResponse("issue-1", "PRD", "DRAFT");
        when(workItemService.listIssues(eq("proj-1"), eq("PRD"), isNull(), isNull(), eq(testUser)))
                .thenReturn(List.of(r1));

        mockMvc.perform(get("/api/v1/projects/proj-1/issues?type=PRD")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("PRD"));
    }

    @Test
    void listIssuesFiltersByStatus() throws Exception {
        IssueResponse r1 = buildIssueResponse("issue-1", "FEATURE_REQUEST", "IN_REVIEW");
        when(workItemService.listIssues(eq("proj-1"), isNull(), eq("IN_REVIEW"), isNull(), eq(testUser)))
                .thenReturn(List.of(r1));

        mockMvc.perform(get("/api/v1/projects/proj-1/issues?status=IN_REVIEW")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("IN_REVIEW"));
    }

    @Test
    void patchIssueValidTransitionReturns200() throws Exception {
        IssueResponse response = buildIssueResponse("issue-1", "PRD", "IN_REVIEW");
        when(workItemService.patchIssue(eq("proj-1"), eq("issue-1"), any(), eq(testUser))).thenReturn(response);

        mockMvc.perform(patch("/api/v1/projects/proj-1/issues/issue-1")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"IN_REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));
    }

    @Test
    void patchIssueInvalidTransitionReturns400() throws Exception {
        when(workItemService.patchIssue(eq("proj-1"), eq("issue-1"), any(), eq(testUser)))
                .thenThrow(new BusinessException("Invalid status transition from DRAFT to READY_FOR_DEVELOPMENT"));

        mockMvc.perform(patch("/api/v1/projects/proj-1/issues/issue-1")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"READY_FOR_DEVELOPMENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid status transition from DRAFT to READY_FOR_DEVELOPMENT"));
    }

    @Test
    void getIssueReturns404WhenNotFound() throws Exception {
        when(workItemService.getIssue(eq("proj-1"), eq("nonexistent"), eq(testUser)))
                .thenThrow(new EntityNotFoundException("Issue not found"));

        mockMvc.perform(get("/api/v1/projects/proj-1/issues/nonexistent")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createIssueRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/projects/proj-1/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"PRD\", \"title\": \"Test\"}"))
                .andExpect(status().isUnauthorized());
    }
}
