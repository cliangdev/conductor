package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.generated.model.ProjectDetail;
import com.conductor.generated.model.ProjectResponse;
import com.conductor.generated.model.ProjectSummary;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProjectController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

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
        testUser.setEmail("creator@example.com");
        testUser.setName("Creator User");

        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-id-123");
        when(userRepository.findById("user-id-123")).thenReturn(Optional.of(testUser));
    }

    @Test
    void createProjectReturns201WithProjectDto() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        ProjectResponse response = new ProjectResponse("proj-1", "My Project", "MYPR", "user-id-123", now)
                .description("A test project");

        when(projectService.createProject(any(), eq(testUser))).thenReturn(response);

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"My Project\", \"description\": \"A test project\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("proj-1"))
                .andExpect(jsonPath("$.name").value("My Project"))
                .andExpect(jsonPath("$.createdBy").value("user-id-123"));
    }

    @Test
    void createProjectWithNameOver100CharsReturns400() throws Exception {
        String longName = "a".repeat(101);

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + longName + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void createProjectWithMissingNameReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"no name here\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listProjectsReturnsOnlyCallerProjects() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        List<ProjectSummary> summaries = List.of(
                new ProjectSummary("proj-1", "Project One", "PONE", 2, now).role("ADMIN"),
                new ProjectSummary("proj-2", "Project Two", "PTWO", 3, now).role("CREATOR")
        );

        when(projectService.listProjects(testUser)).thenReturn(summaries);

        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("proj-1"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[1].id").value("proj-2"))
                .andExpect(jsonPath("$[1].role").value("CREATOR"));
    }

    @Test
    void listProjectsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getProjectReturnsDetailForMember() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        ProjectDetail detail = new ProjectDetail("proj-1", "My Project", "MYPR", "user-id-123", 3, now)
                .description("desc");

        when(projectService.getProject("proj-1", testUser)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/projects/proj-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("proj-1"))
                .andExpect(jsonPath("$.memberCount").value(3));
    }

    @Test
    void getProjectReturns404ForNonMember() throws Exception {
        when(projectService.getProject("proj-unknown", testUser))
                .thenThrow(new EntityNotFoundException("Project not found"));

        mockMvc.perform(get("/api/v1/projects/proj-unknown")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
    }
}
