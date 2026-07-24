package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectApiKey;
import com.conductor.entity.RuntimeTarget;
import com.conductor.entity.RuntimeTargetStatus;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.generated.model.CreateRuntimeTargetRequest;
import com.conductor.generated.model.RuntimeTargetResponse;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.workflow.RunTokenService;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.RuntimeTargetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RuntimeTargetController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class RuntimeTargetControllerTest {

    private static final String PROJECT_ID = "proj-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private RuntimeTargetService runtimeTargetService;
    @MockitoBean private ProjectSecurityService projectSecurityService;

    // Security filter chain collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

    @BeforeEach
    void setUp() {
        User memberUser = new User();
        memberUser.setId("member-user-id");
        memberUser.setEmail("member@example.com");
        memberUser.setName("Member User");

        when(jwtService.validateToken("member-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("member-token")).thenReturn("member-user-id");
        when(userRepository.findById("member-user-id")).thenReturn(Optional.of(memberUser));

        // A project-scoped API key for PROJECT_ID -- ApiKeyAuthenticationFilter resolves this token
        // to an ApiKeyAuthenticationToken (a ProjectScopedPrincipal) with no backing User.
        Project project = new Project();
        project.setId(PROJECT_ID);
        ProjectApiKey apiKey = new ProjectApiKey();
        apiKey.setId("key-1");
        apiKey.setProject(project);
        apiKey.setName("ci-key");
        apiKey.setKeyValue("project-api-key");
        when(projectApiKeyRepository.findByKeyValueWithProject("project-api-key"))
                .thenReturn(Optional.of(apiKey));
    }

    // ---- project API key auth (bug fix regression coverage) ----

    @Test
    void listRuntimeTargets_projectApiKey_succeedsAsMemberLevel() throws Exception {
        when(runtimeTargetService.list(PROJECT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/runtime-targets")
                        .header("Authorization", "Bearer project-api-key"))
                .andExpect(status().isOk());
    }

    @Test
    void createRuntimeTarget_projectApiKey_returnsClean403NotServerError() throws Exception {
        String body = """
                {"name":"my-target","provider":"gcp-cloud-run","connectionId":"conn-1",
                 "gcpProjectId":"customer-proj","region":"us-central1","image":"img:1"}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/runtime-targets")
                        .header("Authorization", "Bearer project-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    private RuntimeTarget targetWithConfig() {
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProjectId(PROJECT_ID);
        target.setName("my-target");
        target.setProvider("gcp-cloud-run");
        target.setConnectionId("conn-1");
        target.setStatus(RuntimeTargetStatus.ACTIVE);
        target.setCreatedAt(OffsetDateTime.now());
        target.setUpdatedAt(OffsetDateTime.now());
        return target;
    }

    private RuntimeTargetService.TargetRuntimeConfig config() {
        return new RuntimeTargetService.TargetRuntimeConfig(
                "customer-proj", "us-central1", "conductor-my-target", "img:1", List.of(), null, null);
    }

    /** Mirrors {@code RuntimeTargetService.toResponse} — the controller now delegates entirely to that
     *  (mocked here) service method, so tests build the expected response the same way it would. */
    private RuntimeTargetResponse responseFor(RuntimeTarget target, RuntimeTargetService.TargetRuntimeConfig config) {
        RuntimeTargetResponse response = new RuntimeTargetResponse()
                .id(target.getId())
                .name(target.getName())
                .provider(target.getProvider())
                .connectionId(target.getConnectionId())
                .gcpProjectId(config.gcpProjectId())
                .region(config.region())
                .jobName(config.jobName())
                .image(config.image())
                .status(RuntimeTargetResponse.StatusEnum.fromValue(target.getStatus().name()))
                .errorMessage(target.getErrorMessage())
                .resolvedImage(config.resolvedImage())
                .lastProvisionedAt(config.lastProvisionedAt())
                .createdAt(target.getCreatedAt())
                .updatedAt(target.getUpdatedAt());
        config.warnings().forEach(response::addWarningsItem);
        return response;
    }

    // ---- listRuntimeTargets ----

    @Test
    void listRuntimeTargets_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/runtime-targets")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listRuntimeTargets_member_returns200WithMappedFields() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(true);
        RuntimeTarget target = targetWithConfig();
        when(runtimeTargetService.list(PROJECT_ID)).thenReturn(List.of(target));
        when(runtimeTargetService.toResponse(target)).thenReturn(responseFor(target, config()));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/runtime-targets")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("target-1"))
                .andExpect(jsonPath("$[0].name").value("my-target"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].gcpProjectId").value("customer-proj"))
                .andExpect(jsonPath("$[0].warnings").doesNotExist());
    }

    // ---- createRuntimeTarget ----

    @Test
    void createRuntimeTarget_nonAdminOrCreator_returns403() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(false);

        String body = """
                {"name":"my-target","provider":"gcp-cloud-run","connectionId":"conn-1",
                 "gcpProjectId":"customer-proj","region":"us-central1","image":"img:1"}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/runtime-targets")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void createRuntimeTarget_happyPath_returns201() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(true);
        RuntimeTarget created = targetWithConfig();
        when(runtimeTargetService.create(eq(PROJECT_ID), any(CreateRuntimeTargetRequest.class))).thenReturn(created);
        when(runtimeTargetService.toResponse(created)).thenReturn(responseFor(created, config()));

        String body = """
                {"name":"my-target","provider":"gcp-cloud-run","connectionId":"conn-1",
                 "gcpProjectId":"customer-proj","region":"us-central1","image":"img:1"}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/runtime-targets")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("target-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createRuntimeTarget_reservedName_returns409() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(runtimeTargetService.create(eq(PROJECT_ID), any(CreateRuntimeTargetRequest.class)))
                .thenThrow(new ConflictException("'cloud-run' is a reserved runs-on value"));

        String body = """
                {"name":"cloud-run","provider":"gcp-cloud-run","connectionId":"conn-1",
                 "gcpProjectId":"customer-proj","region":"us-central1","image":"img:1"}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/runtime-targets")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void createRuntimeTarget_withErrorStatusAndMessage_stillReturns201WithErrorBody() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(true);
        RuntimeTarget errored = targetWithConfig();
        errored.setStatus(RuntimeTargetStatus.ERROR);
        errored.setErrorMessage("Image not found in Artifact Registry repository my-repo");
        when(runtimeTargetService.create(eq(PROJECT_ID), any(CreateRuntimeTargetRequest.class))).thenReturn(errored);
        when(runtimeTargetService.toResponse(errored)).thenReturn(responseFor(errored, config()));

        String body = """
                {"name":"my-target","provider":"gcp-cloud-run","connectionId":"conn-1",
                 "gcpProjectId":"customer-proj","region":"us-central1","image":"img:1"}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/runtime-targets")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorMessage").value("Image not found in Artifact Registry repository my-repo"));
    }

    @Test
    void createRuntimeTarget_warningsSerialized() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(true);
        RuntimeTarget created = targetWithConfig();
        when(runtimeTargetService.create(eq(PROJECT_ID), any(CreateRuntimeTargetRequest.class))).thenReturn(created);
        RuntimeTargetService.TargetRuntimeConfig warningConfig = new RuntimeTargetService.TargetRuntimeConfig(
                "customer-proj", "us-central1", "conductor-my-target", "img:1",
                List.of("Image found. Could not verify the dev.conductor.runner.protocol OCI label."), null, null);
        when(runtimeTargetService.toResponse(created)).thenReturn(responseFor(created, warningConfig));

        String body = """
                {"name":"my-target","provider":"gcp-cloud-run","connectionId":"conn-1",
                 "gcpProjectId":"customer-proj","region":"us-central1","image":"img:1"}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/runtime-targets")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("protocol")));
    }

    // ---- updateRuntimeTarget ----

    @Test
    void updateRuntimeTarget_nonAdminOrCreator_returns403() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/runtime-targets/target-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"us-east1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRuntimeTarget_happyPath_returns200() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(true);
        RuntimeTarget updated = targetWithConfig();
        when(runtimeTargetService.update(eq(PROJECT_ID), eq("target-1"), any())).thenReturn(updated);
        when(runtimeTargetService.toResponse(updated)).thenReturn(responseFor(updated, config()));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/runtime-targets/target-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"us-east1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("target-1"));
    }

    // ---- deleteRuntimeTarget ----

    @Test
    void deleteRuntimeTarget_nonAdminOrCreator_returns403() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/projects/" + PROJECT_ID + "/runtime-targets/target-1")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteRuntimeTarget_happyPath_returns204() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/projects/" + PROJECT_ID + "/runtime-targets/target-1")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isNoContent());
    }

    // ---- provisionRuntimeTarget ----

    @Test
    void provisionRuntimeTarget_nonAdminOrCreator_returns403() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/runtime-targets/target-1/provision")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void provisionRuntimeTarget_happyPath_returns200() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(true);
        RuntimeTarget target = targetWithConfig();
        when(runtimeTargetService.provisionById(PROJECT_ID, "target-1")).thenReturn(target);
        when(runtimeTargetService.toResponse(target)).thenReturn(responseFor(target, config()));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/runtime-targets/target-1/provision")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
