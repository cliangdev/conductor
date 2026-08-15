package com.conductor.controller;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentAvatarDefaults;
import com.conductor.agent.AgentService;
import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.credential.ProviderCredentialService.ProviderCredentialStatusView;
import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ModelInfo;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectApiKey;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.workflow.RunTokenService;
import com.conductor.repository.UserRepository;
import com.conductor.service.ClaudeRuntimeService;
import com.conductor.service.JwtService;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.ProviderVerificationService;
import com.conductor.service.RuntimeTargetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class AgentControllerTest {

    private static final String PROJECT_ID = "proj-1";

    @Autowired
    private MockMvc mockMvc;

    // AgentController collaborators
    @MockitoBean private AgentService agentService;
    @MockitoBean private ProviderCredentialService providerCredentialService;
    @MockitoBean private ProviderVerificationService providerVerificationService;
    @MockitoBean private ClaudeRuntimeService claudeRuntimeService;
    @MockitoBean private RuntimeTargetService runtimeTargetService;
    @MockitoBean private ProjectMemberRepository projectMemberRepository;
    @MockitoBean private ModelProviderRegistry providerRegistry;
    @MockitoBean private ObjectMapper objectMapper;

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
        when(userRepository.findById("member-user-id")).thenReturn(java.util.Optional.of(memberUser));

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
                .thenReturn(java.util.Optional.of(apiKey));
    }

    // ---- project API key auth (bug fix regression coverage) ----
    // AgentController.currentUser() used to unconditionally cast the Authentication principal to
    // User, which threw ClassCastException (-> 500) whenever the caller authenticated with a
    // project API key (ApiKeyAuthenticationToken, principal = plain project-id String). Member-level
    // endpoints must now accept the project-scoped principal outright; admin/creator-level endpoints
    // must reject it with a clean 403, never a 500 -- mirroring KnowledgeController's precedent.

    @Test
    void listAgents_projectApiKey_succeedsAsMemberLevel() throws Exception {
        when(agentService.list(PROJECT_ID)).thenReturn(List.of(stubAgent()));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents")
                        .header("Authorization", "Bearer project-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getAgent_projectApiKey_succeedsAsMemberLevel() throws Exception {
        when(agentService.get(PROJECT_ID, "agent-1")).thenReturn(stubAgent());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer project-api-key"))
                .andExpect(status().isOk());
    }

    @Test
    void createAgent_projectApiKey_returnsClean403NotServerError() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/agents")
                        .header("Authorization", "Bearer project-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Marketer\",\"provider\":\"claude\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateAgent_projectApiKey_returnsClean403NotServerError() throws Exception {
        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer project-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- isDefault mapping ----

    @Test
    void getAgent_seededLibrarianSlug_isDefaultTrue() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        Agent librarian = stubAgent();
        librarian.setSlug("knowledge-librarian");
        when(agentService.get(PROJECT_ID, "agent-1")).thenReturn(librarian);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void getAgent_userCreatedSlug_isDefaultFalse() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(agentService.get(PROJECT_ID, "agent-1")).thenReturn(stubAgent());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(false));
    }

    // ---- addressable mapping ----

    @Test
    void getAgent_configAddressableTrue_addressableTrue() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        Agent agent = stubAgent();
        agent.setConfigJson("{\"addressable\":true}");
        when(agentService.get(PROJECT_ID, "agent-1")).thenReturn(agent);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressable").value(true));
    }

    @Test
    void getAgent_configAddressableAbsent_addressableFalse() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(agentService.get(PROJECT_ID, "agent-1")).thenReturn(stubAgent());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressable").value(false));
    }

    // ---- listAgentTools ----

    @Test
    void listAgentTools_happyPath_returnsToolsTaggedWithSource() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(agentService.listAvailableTools(PROJECT_ID)).thenReturn(List.of(
                new AgentService.ToolOption(
                        "connector:posthog/web_analytics_summary",
                        "posthog_web_analytics_summary",
                        "Summarize web analytics",
                        "connector")));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/tools")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("connector:posthog/web_analytics_summary"))
                .andExpect(jsonPath("$[0].name").value("posthog_web_analytics_summary"))
                .andExpect(jsonPath("$[0].source").value("connector"));
    }

    @Test
    void listAgentTools_nonMember_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/tools")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    // ---- listAgentProviders ----

    @Test
    void listAgentProviders_happyPath_returnsProvidersWithDefaultModel() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(agentService.listProviders()).thenReturn(List.of(
                new AgentService.ProviderOption("claude", "claude-opus-4-8", false)));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("claude"))
                .andExpect(jsonPath("$[0].defaultModel").value("claude-opus-4-8"))
                .andExpect(jsonPath("$[0].defaultModelIsLive").value(false));
    }

    @Test
    void listAgentProviders_nonMember_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    // ---- listProviderModels ----

    @Test
    void listProviderModels_nonAdmin_returns403() throws Exception {
        // ADMIN/CREATOR-gated, not just member -- this decrypts and spends the project's key against
        // the vendor, same trigger as verifyProviderCredential.
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.REVIEWER)));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers/openai/models")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listProviderModels_unknownProvider_returnsEmptyListNot500() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        when(providerRegistry.findById("not-a-provider")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers/not-a-provider/models")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models.length()").value(0));
    }

    @Test
    void listProviderModels_noStoredCredential_returnsEmptyListWithoutCallingProvider() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        ChatModelProvider provider = org.mockito.Mockito.mock(ChatModelProvider.class);
        when(providerRegistry.findById("openai")).thenReturn(Optional.of(provider));
        when(providerCredentialService.resolveApiKey(PROJECT_ID, "openai")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers/openai/models")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models.length()").value(0));

        verify(provider, org.mockito.Mockito.never()).availableModels(any());
    }

    @Test
    void listProviderModels_populated_returnsIdsAndLatestFlag() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        ChatModelProvider provider = org.mockito.Mockito.mock(ChatModelProvider.class);
        when(providerRegistry.findById("openai")).thenReturn(Optional.of(provider));
        when(providerCredentialService.resolveApiKey(PROJECT_ID, "openai")).thenReturn(Optional.of("sk-test"));
        when(provider.availableModels("sk-test")).thenReturn(List.of(
                new ModelInfo("gpt-5.6-sol", true),
                new ModelInfo("gpt-5.5", false)));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers/openai/models")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models.length()").value(2))
                .andExpect(jsonPath("$.models[0].id").value("gpt-5.6-sol"))
                .andExpect(jsonPath("$.models[0].latest").value(true))
                .andExpect(jsonPath("$.models[1].id").value("gpt-5.5"))
                .andExpect(jsonPath("$.models[1].latest").value(false));
    }

    // ---- listProviderCredentialStatuses ----

    @Test
    void listProviderCredentialStatuses_happyPath_returnsAllProviderStatuses() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(providerCredentialService.listStatuses(PROJECT_ID)).thenReturn(List.of(
                new ProviderCredentialStatusView("claude", true),
                new ProviderCredentialStatusView("claude-code", false)));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers/credentials")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].provider").value("claude"))
                .andExpect(jsonPath("$[0].configured").value(true))
                .andExpect(jsonPath("$[1].provider").value("claude-code"))
                .andExpect(jsonPath("$[1].configured").value(false));
    }

    @Test
    void listProviderCredentialStatuses_nonMember_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers/credentials")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listProviderCredentialStatuses_carriesVerificationFields() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        // AgentController's ObjectMapper is a @MockitoBean here (WebMvcTest slice) — delegate readTree
        // to a real Jackson instance so firstFailingCheckMessage's parsing is genuinely exercised.
        com.fasterxml.jackson.databind.ObjectMapper realMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(objectMapper.readTree(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> realMapper.readTree((String) inv.getArgument(0)));

        java.time.OffsetDateTime checkedAt = java.time.OffsetDateTime.parse("2026-07-01T00:00:00Z");
        when(providerCredentialService.listStatuses(PROJECT_ID)).thenReturn(List.of(
                new ProviderCredentialStatusView("claude", true, "error", checkedAt,
                        "{\"checks\":[{\"name\":\"anthropic-api\",\"status\":\"fail\",\"message\":\"bad key\"}]}"),
                new ProviderCredentialStatusView("claude-code", false)));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers/credentials")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].verification.status").value("error"))
                .andExpect(jsonPath("$[0].verification.error").value("bad key"))
                .andExpect(jsonPath("$[1].verification").doesNotExist());
    }

    // ---- setProviderCredential / verifyProviderCredential ----

    @Test
    void setProviderCredential_nonAdmin_returns403() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.REVIEWER)));

        mockMvc.perform(put("/api/v1/projects/" + PROJECT_ID + "/agents/providers/claude/credential")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void setProviderCredential_savesThenVerifiesAndCarriesVerificationInResponse() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        java.time.OffsetDateTime checkedAt = java.time.OffsetDateTime.parse("2026-07-01T00:00:00Z");
        when(providerCredentialService.getStatus(PROJECT_ID, "claude")).thenReturn(
                new ProviderCredentialStatusView("claude", true, "verified", checkedAt, "{\"checks\":[]}"));

        mockMvc.perform(put("/api/v1/projects/" + PROJECT_ID + "/agents/providers/claude/credential")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.verification.status").value("verified"));

        verify(providerCredentialService).setApiKey(PROJECT_ID, "claude", "sk-test");
        verify(providerVerificationService).verify(PROJECT_ID, "claude");
    }

    @Test
    void setProviderCredential_verifyThrows_putStillSucceeds() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        when(providerVerificationService.verify(PROJECT_ID, "claude"))
                .thenThrow(new RuntimeException("boom"));
        when(providerCredentialService.getStatus(PROJECT_ID, "claude")).thenReturn(
                new ProviderCredentialStatusView("claude", true));

        mockMvc.perform(put("/api/v1/projects/" + PROJECT_ID + "/agents/providers/claude/credential")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void verifyProviderCredential_nonAdmin_returns403() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.REVIEWER)));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/agents/providers/claude/credential/verify")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void verifyProviderCredential_happyPath_returnsReport() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        java.time.OffsetDateTime checkedAt = java.time.OffsetDateTime.parse("2026-07-01T00:00:00Z");
        when(providerVerificationService.verify(PROJECT_ID, "claude")).thenReturn(
                new ProviderVerificationService.VerificationReport("claude",
                        ProviderVerificationService.ReportStatus.VERIFIED, checkedAt,
                        List.of(new com.conductor.verification.Check("anthropic-api",
                                com.conductor.verification.CheckStatus.PASS, "ok"))));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/agents/providers/claude/credential/verify")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("claude"))
                .andExpect(jsonPath("$.status").value("verified"))
                .andExpect(jsonPath("$.checks[0].name").value("anthropic-api"))
                .andExpect(jsonPath("$.checks[0].status").value("pass"));
    }

    // ---- getClaudeRuntime / setClaudeRuntime ----

    @Test
    void getClaudeRuntime_nonMember_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers/claude-code/runtime")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getClaudeRuntime_builtinSource_returnsConfigWithoutTarget() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(claudeRuntimeService.getConfig(PROJECT_ID)).thenReturn(
                new ClaudeRuntimeService.ClaudeRuntimeConfig("builtin", null, null, true));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers/claude-code/runtime")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("builtin"))
                .andExpect(jsonPath("$.builtinConfigured").value(true))
                .andExpect(jsonPath("$.runtimeTarget").doesNotExist());
    }

    @Test
    void setClaudeRuntime_nonAdmin_returns403() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.REVIEWER)));

        mockMvc.perform(put("/api/v1/projects/" + PROJECT_ID + "/agents/providers/claude-code/runtime")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runtimeTargetId\":\"target-1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void setClaudeRuntime_happyPath_returnsDesignatedTargetConfig() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        com.conductor.entity.RuntimeTarget target = new com.conductor.entity.RuntimeTarget();
        target.setId("target-1");
        target.setName("my-target");
        target.setProvider("gcp-cloud-run");
        target.setStatus(com.conductor.entity.RuntimeTargetStatus.ACTIVE);
        target.setConfigJson("{}");
        target.setCreatedAt(java.time.OffsetDateTime.now());
        target.setUpdatedAt(java.time.OffsetDateTime.now());
        when(claudeRuntimeService.setTarget(PROJECT_ID, "target-1")).thenReturn(
                new ClaudeRuntimeService.ClaudeRuntimeConfig("project-target", "target-1", target, true));
        when(runtimeTargetService.toResponse(target)).thenReturn(
                new com.conductor.generated.model.RuntimeTargetResponse()
                        .id("target-1").name("my-target")
                        .status(com.conductor.generated.model.RuntimeTargetResponse.StatusEnum.ACTIVE));

        mockMvc.perform(put("/api/v1/projects/" + PROJECT_ID + "/agents/providers/claude-code/runtime")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runtimeTargetId\":\"target-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("project-target"))
                .andExpect(jsonPath("$.runtimeTargetId").value("target-1"))
                .andExpect(jsonPath("$.runtimeTarget.name").value("my-target"));
    }

    // ---- agent config "runtime" round-trip (bug fix: was silently dropped by toConfigMap) ----

    @Test
    void createAgent_withRuntimeConfig_passesRuntimeThroughToService() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        when(agentService.create(eq(PROJECT_ID), any())).thenReturn(stubAgent());

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/agents")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Marketer\",\"provider\":\"claude\","
                                + "\"config\":{\"runtime\":\"claude-code\"}}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<AgentService.AgentInput> captor = ArgumentCaptor.forClass(AgentService.AgentInput.class);
        verify(agentService).create(eq(PROJECT_ID), captor.capture());
        assertThat(captor.getValue().config()).containsEntry("runtime", "claude-code");
    }

    @Test
    void updateAgent_withRuntimeConfig_passesRuntimeThroughToService() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        when(agentService.update(eq(PROJECT_ID), eq("agent-1"), any())).thenReturn(stubAgent());

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config\":{\"runtime\":\"api\"}}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AgentService.AgentInput> captor = ArgumentCaptor.forClass(AgentService.AgentInput.class);
        verify(agentService).update(eq(PROJECT_ID), eq("agent-1"), captor.capture());
        assertThat(captor.getValue().config()).containsEntry("runtime", "api");
    }

    @Test
    void createAgent_invalidRuntimeValue_returns400() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/agents")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Marketer\",\"provider\":\"claude\","
                                + "\"config\":{\"runtime\":\"not-a-real-runtime\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAgent_persistedRuntimeConfig_returnsRuntimeInResponse() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        // AgentController's ObjectMapper is a @MockitoBean here (WebMvcTest slice) — delegate to a
        // real Jackson instance so readConfig's deserialization is genuinely exercised, confirming
        // AgentService's execution-time resolution (which reads configJson's "runtime" key) would
        // actually see the persisted value.
        com.fasterxml.jackson.databind.ObjectMapper realMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(objectMapper.readValue(org.mockito.ArgumentMatchers.anyString(),
                        eq(com.conductor.generated.model.AgentConfig.class)))
                .thenAnswer(inv -> realMapper.readValue((String) inv.getArgument(0),
                        com.conductor.generated.model.AgentConfig.class));

        Agent agent = stubAgent();
        agent.setConfigJson("{\"runtime\":\"claude-code\"}");
        when(agentService.get(PROJECT_ID, "agent-1")).thenReturn(agent);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.runtime").value("claude-code"));
    }

    // ---- avatar ----

    @Test
    void createAgent_withoutAvatar_returnsResponseWithDerivedDefaults() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        when(agentService.create(eq(PROJECT_ID), any())).thenReturn(stubAgent());

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/agents")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Marketer\",\"provider\":\"claude\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.avatarEmoji").value(AgentAvatarDefaults.defaultEmoji("marketer")))
                .andExpect(jsonPath("$.avatarColor").value(AgentAvatarDefaults.defaultColor("marketer")));
    }

    @Test
    void createAgent_withExplicitAvatar_persistedValuesAreReturned() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        Agent created = stubAgent();
        created.setAvatarEmoji("🦉");
        created.setAvatarColor("teal");
        when(agentService.create(eq(PROJECT_ID), any())).thenReturn(created);

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/agents")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Marketer\",\"provider\":\"claude\",\"avatarEmoji\":\"🦉\",\"avatarColor\":\"teal\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.avatarEmoji").value("🦉"))
                .andExpect(jsonPath("$.avatarColor").value("teal"));

        ArgumentCaptor<AgentService.AgentInput> captor = ArgumentCaptor.forClass(AgentService.AgentInput.class);
        verify(agentService).create(eq(PROJECT_ID), captor.capture());
        assertThat(captor.getValue().avatarEmoji()).isEqualTo("🦉");
        assertThat(captor.getValue().avatarColor()).isEqualTo("teal");
    }

    @Test
    void updateAgent_avatarFields_passedThroughToService() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        when(agentService.update(eq(PROJECT_ID), eq("agent-1"), any())).thenReturn(stubAgent());

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarEmoji\":\"🚀\",\"avatarColor\":\"rose\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AgentService.AgentInput> captor = ArgumentCaptor.forClass(AgentService.AgentInput.class);
        verify(agentService).update(eq(PROJECT_ID), eq("agent-1"), captor.capture());
        assertThat(captor.getValue().avatarEmoji()).isEqualTo("🚀");
        assertThat(captor.getValue().avatarColor()).isEqualTo("rose");
    }

    @Test
    void createAgent_unknownAvatarColorToken_returns400() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/agents")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Marketer\",\"provider\":\"claude\",\"avatarColor\":\"not-a-color\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- request-body enum rejection ----
    // A bad enum token used to surface as the generic "Malformed or unreadable request body", which told
    // the caller nothing about which field was wrong or what it accepts.

    @Test
    void createAgent_unknownAvatarColorToken_detailNamesFieldAndAcceptedValues() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/agents")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Marketer\",\"provider\":\"claude\",\"avatarColor\":\"purple\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Invalid value for field 'avatarColor' — must be one of: "
                                + "gray, blue, amber, violet, teal, green, rose, slate"));
    }

    @Test
    void createAgent_malformedJsonBody_keepsGenericUnreadableDetail() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/agents")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Marketer\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed or unreadable request body"));
    }

    // ---- updateAgent toolIds partial-update semantics ----
    // Guards the regression where a partial PATCH that omits toolIds (e.g. the Active/Draft state
    // toggle) wiped an agent's tool bindings: the generated request defaulted toolIds to an empty
    // list, so the service's "null == unchanged" guard never fired. UpdateAgentRequest.toolIds is now
    // nullable, so an omitted array deserializes to null (unchanged) while an explicit [] still clears.

    @Test
    void updateAgent_omitsToolIds_passesNullToServiceSoBindingsAreUnchanged() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        when(agentService.update(eq(PROJECT_ID), eq("agent-1"), any())).thenReturn(stubAgent());

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"DRAFT\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AgentService.AgentInput> captor = ArgumentCaptor.forClass(AgentService.AgentInput.class);
        verify(agentService).update(eq(PROJECT_ID), eq("agent-1"), captor.capture());
        assertThat(captor.getValue().toolIds()).as("omitted toolIds must be null (unchanged)").isNull();
    }

    @Test
    void updateAgent_explicitEmptyToolIds_passesEmptyListToServiceSoBindingsClear() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        when(agentService.update(eq(PROJECT_ID), eq("agent-1"), any())).thenReturn(stubAgent());

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolIds\":[]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AgentService.AgentInput> captor = ArgumentCaptor.forClass(AgentService.AgentInput.class);
        verify(agentService).update(eq(PROJECT_ID), eq("agent-1"), captor.capture());
        assertThat(captor.getValue().toolIds()).as("explicit empty toolIds must clear bindings").isNotNull().isEmpty();
    }

    @Test
    void updateAgent_nonAdmin_returns403() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.REVIEWER)));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"DRAFT\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- deleteAgent ----

    @Test
    void deleteAgent_happyPath_returns204() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));

        mockMvc.perform(delete("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isNoContent());

        verify(agentService).delete(PROJECT_ID, "agent-1");
    }

    @Test
    void deleteAgent_nonAdmin_returns403() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.REVIEWER)));

        mockMvc.perform(delete("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteAgent_referencedByWorkflow_returns409WithConflictsList() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        doThrow(new com.conductor.agent.AgentReferencedByWorkflowsException(
                List.of(new com.conductor.agent.AgentReferencedByWorkflowsException.Reference("wf-1", "PR Review"))))
                .when(agentService).delete(PROJECT_ID, "agent-1");

        mockMvc.perform(delete("/api/v1/projects/" + PROJECT_ID + "/agents/agent-1")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts[0].workflowId").value("wf-1"))
                .andExpect(jsonPath("$.conflicts[0].workflowName").value("PR Review"));
    }

    /** Minimal persisted agent whose null config/toolIds let the response map without the mocked ObjectMapper. */
    private Agent stubAgent() {
        Agent agent = new Agent();
        agent.setId("agent-1");
        agent.setProjectId(PROJECT_ID);
        agent.setName("Marketer");
        agent.setSlug("marketer");
        agent.setProvider("claude");
        agent.setState("DRAFT");
        return agent;
    }

    /** A membership row carrying {@code role} — what the real ProjectSecurityService reads. */
    private static ProjectMember memberWithRole(MemberRole role) {
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        return member;
    }
}
