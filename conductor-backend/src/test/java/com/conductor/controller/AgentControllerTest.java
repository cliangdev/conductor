package com.conductor.controller;

import com.conductor.agent.AgentService;
import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AgentControllerTest {

    private static final String PROJECT_ID = "proj-1";

    @Autowired
    private MockMvc mockMvc;

    // AgentController collaborators
    @MockitoBean private AgentService agentService;
    @MockitoBean private ProviderCredentialService providerCredentialService;
    @MockitoBean private ProjectSecurityService projectSecurityService;
    @MockitoBean private ObjectMapper objectMapper;

    // Security filter chain collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;

    @BeforeEach
    void setUp() {
        User memberUser = new User();
        memberUser.setId("member-user-id");
        memberUser.setEmail("member@example.com");
        memberUser.setName("Member User");

        when(jwtService.validateToken("member-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("member-token")).thenReturn("member-user-id");
        when(userRepository.findById("member-user-id")).thenReturn(java.util.Optional.of(memberUser));
    }

    // ---- listAgentTools ----

    @Test
    void listAgentTools_happyPath_returnsToolsTaggedWithSource() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(true);
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
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/tools")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    // ---- listAgentProviders ----

    @Test
    void listAgentProviders_happyPath_returnsProvidersWithDefaultModel() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(agentService.listProviders()).thenReturn(List.of(
                new AgentService.ProviderOption("claude", "claude-opus-4-8")));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("claude"))
                .andExpect(jsonPath("$[0].defaultModel").value("claude-opus-4-8"));
    }

    @Test
    void listAgentProviders_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/agents/providers")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }
}
