package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.Connection;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.integration.AuthType;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.FieldType;
import com.conductor.integration.connector.GcpBillingConnector;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.IntegrationFetchService;
import com.conductor.service.JwtService;
import com.conductor.service.OAuthFlowService;
import com.conductor.service.ProjectSecurityService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IntegrationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class IntegrationControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String GCP_CONNECTOR_ID = "gcp-billing";
    private static final String GCP_SA_CONNECTOR_ID = "gcp";

    @Autowired
    private MockMvc mockMvc;

    // IntegrationController collaborators
    @MockitoBean private ConnectorRegistry connectorRegistry;
    @MockitoBean private ConnectionService connectionService;
    @MockitoBean private IntegrationFetchService fetchService;
    @MockitoBean private OAuthFlowService oAuthFlowService;
    @MockitoBean private ConnectionDataCacheRepository cacheRepository;
    @MockitoBean private WebhookEventRepository webhookEventRepository;
    @MockitoBean private ProjectSecurityService projectSecurityService;
    @MockitoBean private GcpBillingConnector gcpBillingConnector;
    @MockitoBean private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // Security filter chain collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;

    private User memberUser;

    @BeforeEach
    void setUp() {
        memberUser = new User();
        memberUser.setId("member-user-id");
        memberUser.setEmail("member@example.com");
        memberUser.setName("Member User");

        when(jwtService.validateToken("member-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("member-token")).thenReturn("member-user-id");
        when(userRepository.findById("member-user-id")).thenReturn(Optional.of(memberUser));
    }

    private Connection gcpConnectionWithToken() {
        Connection conn = new Connection();
        conn.setId("gcp-conn-1");
        conn.setProjectId(PROJECT_ID);
        conn.setConnectorId(GCP_CONNECTOR_ID);
        conn.setAuthType("OAUTH2");
        return conn;
    }

    // ---- listGcpProjects ----

    @Test
    void listGcpProjects_happyPath_returnsProjects() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(true);
        Connection conn = gcpConnectionWithToken();
        when(connectionService.findSingle(PROJECT_ID, GCP_CONNECTOR_ID)).thenReturn(Optional.of(conn));
        when(connectionService.decrypt(conn))
                .thenReturn(new DecryptedCredentials("tok", "refresh", Instant.now().plusSeconds(3600), Map.of()));
        when(gcpBillingConnector.listGcpProjects("tok")).thenReturn(List.of(
                Map.of("projectId", "p-1", "name", "Project One"),
                Map.of("projectId", "p-2", "name", "Project Two")));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/gcp-billing/gcp-projects")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.length()").value(2))
                .andExpect(jsonPath("$.projects[0].projectId").value("p-1"))
                .andExpect(jsonPath("$.projects[0].name").value("Project One"));
    }

    @Test
    void listGcpProjects_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/gcp-billing/gcp-projects")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listGcpProjects_noOAuthCredentials_isRejected_andDoesNotCallConnector() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(connectionService.findSingle(PROJECT_ID, GCP_CONNECTOR_ID)).thenReturn(Optional.empty());

        // Missing OAuth credentials raises a CONFLICT ResponseStatusException; the application's
        // catch-all @ExceptionHandler(Exception.class) renders it as 5xx (preserved from the former
        // GcpBillingController behavior). The contract under test: the request is rejected and the
        // connector is never invoked.
        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/gcp-billing/gcp-projects")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().is5xxServerError());
        org.mockito.Mockito.verifyNoInteractions(gcpBillingConnector);
    }

    @Test
    void listGcpProjects_refreshesExpiringToken() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(true);
        Connection conn = gcpConnectionWithToken();
        when(connectionService.findSingle(PROJECT_ID, GCP_CONNECTOR_ID)).thenReturn(Optional.of(conn));
        when(connectionService.decrypt(conn))
                .thenReturn(new DecryptedCredentials("stale", "refresh", Instant.now().minusSeconds(10), Map.of()));
        when(oAuthFlowService.refreshAccessToken(eq(conn), eq("refresh"))).thenReturn("fresh");
        when(gcpBillingConnector.listGcpProjects("fresh")).thenReturn(List.of(
                Map.of("projectId", "p-1", "name", "Project One")));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/gcp-billing/gcp-projects")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].projectId").value("p-1"));
    }

    // ---- listBqDatasets ----

    @Test
    void listBqDatasets_happyPath_returnsDatasets() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(true);
        Connection conn = gcpConnectionWithToken();
        when(connectionService.findSingle(PROJECT_ID, GCP_CONNECTOR_ID)).thenReturn(Optional.of(conn));
        when(connectionService.decrypt(conn))
                .thenReturn(new DecryptedCredentials("tok", "refresh", Instant.now().plusSeconds(3600), Map.of()));
        when(gcpBillingConnector.listBqDatasets("tok", "my-gcp-project")).thenReturn(List.of(
                Map.of("datasetId", "billing", "location", "US")));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/gcp-billing/bq-datasets")
                        .param("gcpProjectId", "my-gcp-project")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasets.length()").value(1))
                .andExpect(jsonPath("$.datasets[0].datasetId").value("billing"))
                .andExpect(jsonPath("$.datasets[0].location").value("US"));
    }

    @Test
    void listBqDatasets_invalidProjectId_isRejected_andDoesNotCallConnector() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(true);

        // Invalid gcpProjectId raises a BAD_REQUEST ResponseStatusException, rendered as 5xx by the
        // catch-all handler (preserved behavior). Contract: rejected, and the connector is not called.
        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/gcp-billing/bq-datasets")
                        .param("gcpProjectId", "bad id!")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().is5xxServerError());
        org.mockito.Mockito.verifyNoInteractions(gcpBillingConnector);
    }

    @Test
    void listBqDatasets_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/gcp-billing/bq-datasets")
                        .param("gcpProjectId", "my-gcp-project")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    // ---- createConnection (SERVICE_ACCOUNT) ----

    private Connector gcpConnectorSpecMock() {
        Connector connector = mock(Connector.class);
        when(connector.getSpec()).thenReturn(ConnectorSpec.serviceAccount(false, List.of(
                ConnectorConfigField.userInput("serviceAccountKey", "Service Account Key", "hint",
                        FieldType.JSON, true))));
        return connector;
    }

    @Test
    void createConnection_serviceAccount_happyPath_storesKeyAndNeverEchoesIt() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(true);
        Connector gcpConnector = gcpConnectorSpecMock();
        when(connectorRegistry.getById(GCP_SA_CONNECTOR_ID)).thenReturn(Optional.of(gcpConnector));

        String saKeyJson = "{\"type\":\"service_account\",\"project_id\":\"p\"}";
        when(objectMapper.readValue(eq(saKeyJson), any(TypeReference.class)))
                .thenReturn(Map.of("type", "service_account", "project_id", "p"));

        Connection created = new Connection();
        created.setId("conn-1");
        created.setConnectorId(GCP_SA_CONNECTOR_ID);
        created.setAuthType("SERVICE_ACCOUNT");
        when(connectionService.create(eq(PROJECT_ID), eq(GCP_SA_CONNECTOR_ID), eq(AuthType.SERVICE_ACCOUNT),
                isNull(), eq("member-user-id"))).thenReturn(created);

        String requestBody = "{\"serviceAccountKey\":\"" + saKeyJson.replace("\"", "\\\"") + "\"}";

        var result = mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + GCP_SA_CONNECTOR_ID + "/connections")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("conn-1"))
                .andExpect(jsonPath("$.authType").value("SERVICE_ACCOUNT"))
                .andReturn();

        verify(connectionService).storeTokens(eq(created), eq(saKeyJson), isNull(), isNull());
        assertThat(result.getResponse().getContentAsString()).doesNotContain("project_id", "service_account");
    }

    @Test
    void createConnection_serviceAccount_malformedJson_returns400_andNeverStoresTokens() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(true);
        Connector gcpConnector = gcpConnectorSpecMock();
        when(connectorRegistry.getById(GCP_SA_CONNECTOR_ID)).thenReturn(Optional.of(gcpConnector));
        when(connectionService.create(any(), any(), any(), any(), any())).thenReturn(new Connection());
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenThrow(new RuntimeException("not valid JSON"));

        String requestBody = "{\"serviceAccountKey\":\"not-json\"}";

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + GCP_SA_CONNECTOR_ID + "/connections")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(connectionService, never()).storeTokens(any(), any(), any(), any());
    }

    @Test
    void createConnection_serviceAccount_wrongCredentialType_returns400() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(true);
        Connector gcpConnector = gcpConnectorSpecMock();
        when(connectorRegistry.getById(GCP_SA_CONNECTOR_ID)).thenReturn(Optional.of(gcpConnector));
        when(connectionService.create(any(), any(), any(), any(), any())).thenReturn(new Connection());
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of("type", "authorized_user"));

        String requestBody = "{\"serviceAccountKey\":\"{\\\"type\\\":\\\"authorized_user\\\"}\"}";

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + GCP_SA_CONNECTOR_ID + "/connections")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(connectionService, never()).storeTokens(any(), any(), any(), any());
    }

    // ---- ProjectSecurityService gating on a plain read endpoint ----

    @Test
    void listIntegrations_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listIntegrations_member_returns200() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(connectorRegistry.getAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk());
    }
}
