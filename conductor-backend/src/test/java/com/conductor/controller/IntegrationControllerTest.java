package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.AuthType;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.IntegrationConnector;
import com.conductor.repository.IntegrationCredentialRepository;
import com.conductor.repository.IntegrationDataCacheRepository;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.CredentialService;
import com.conductor.service.IntegrationFetchService;
import com.conductor.service.JwtService;
import com.conductor.service.OAuthFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IntegrationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class IntegrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConnectorRegistry connectorRegistry;
    @MockitoBean
    private IntegrationFetchService fetchService;
    @MockitoBean
    private CredentialService credentialService;
    @MockitoBean
    private OAuthFlowService oAuthFlowService;
    @MockitoBean
    private IntegrationCredentialRepository credentialRepository;
    @MockitoBean
    private IntegrationDataCacheRepository cacheRepository;
    @MockitoBean
    private ProjectMemberRepository projectMemberRepository;
    @MockitoBean
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean
    private UserApiKeyRepository userApiKeyRepository;

    private User adminUser;
    private User reviewerUser;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId("admin-id");
        adminUser.setEmail("admin@example.com");

        reviewerUser = new User();
        reviewerUser.setId("reviewer-id");
        reviewerUser.setEmail("reviewer@example.com");

        when(jwtService.validateToken("admin-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("admin-token")).thenReturn("admin-id");
        when(userRepository.findById("admin-id")).thenReturn(Optional.of(adminUser));

        when(jwtService.validateToken("reviewer-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("reviewer-token")).thenReturn("reviewer-id");
        when(userRepository.findById("reviewer-id")).thenReturn(Optional.of(reviewerUser));

        ProjectMember admin = new ProjectMember();
        admin.setRole(MemberRole.ADMIN);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "admin-id"))
                .thenReturn(Optional.of(admin));

        ProjectMember reviewer = new ProjectMember();
        reviewer.setRole(MemberRole.REVIEWER);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "reviewer-id"))
                .thenReturn(Optional.of(reviewer));
    }

    @Test
    void reviewerCannotConnectIntegration() throws Exception {
        mockMvc.perform(post("/api/v1/projects/proj-1/integrations/stripe/credentials")
                        .header("Authorization", "Bearer reviewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\": \"sk_test_123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListIntegrations() throws Exception {
        when(connectorRegistry.getAll()).thenReturn(List.of(new StubConnector()));
        when(credentialRepository.findByProjectIdAndConnectorId("proj-1", "stub"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/projects/proj-1/integrations")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].connectorId").value("stub"))
                .andExpect(jsonPath("$[0].connected").value(false));
    }

    private static class StubConnector implements IntegrationConnector {
        @Override
        public String getId() {
            return "stub";
        }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("stub", "Stub", ConnectorCategory.ANALYTICS,
                    AuthType.API_KEY, "A stub connector", "ST");
        }

        @Override
        public List<ConnectorConfigField> getConfigFields() {
            return List.of(new ConnectorConfigField("apiKey", "API Key", "Your key", true));
        }

        @Override
        public ConnectorData fetchData(DecryptedCredentials credentials) {
            return ConnectorData.healthy(java.util.Map.of());
        }

        @Override
        public ConnectorHealth checkHealth(DecryptedCredentials credentials) {
            return ConnectorHealth.HEALTHY;
        }
    }
}
