package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.Connection;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.integration.AuthType;
import com.conductor.integration.Capability;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.FieldType;
import com.conductor.integration.connector.GcpBillingConnector;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.workflow.RunTokenService;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.IntegrationFetchService;
import com.conductor.service.JwtService;
import com.conductor.service.OAuthFlowService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.RuntimeTargetService;
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
    @MockitoBean private RuntimeTargetService runtimeTargetService;
    @MockitoBean private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // Security filter chain collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

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

    // ---- deleteConnection ----

    @Test
    void deleteConnection_flipsReferencingRuntimeTargetsBeforeDeleting() throws Exception {
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "member-user-id")).thenReturn(true);
        Connection conn = new Connection();
        conn.setId("conn-9");
        conn.setProjectId(PROJECT_ID);
        conn.setConnectorId(GCP_SA_CONNECTOR_ID);
        when(connectionService.getById("conn-9", GCP_SA_CONNECTOR_ID)).thenReturn(Optional.of(conn));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                                + GCP_SA_CONNECTOR_ID + "/connections/conn-9")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isNoContent());

        // Order matters: targets must be flipped while connection_id is still set (FK is
        // ON DELETE SET NULL — after the delete they'd no longer be findable by connection id).
        var inOrder = org.mockito.Mockito.inOrder(runtimeTargetService, connectionService);
        inOrder.verify(runtimeTargetService).onConnectionDeleted("conn-9");
        inOrder.verify(connectionService).delete("conn-9");
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

    // ---- listConnectorCatalog ----

    @Test
    void listConnectorCatalog_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/catalog")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listConnectorCatalog_member_returnsAllRegisteredConnectors_withActiveConnectionState() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(true);

        Connector connector = mock(Connector.class);
        when(connector.getId()).thenReturn("posthog");
        when(connector.getMetadata()).thenReturn(new ConnectorMetadata(
                "posthog", "PostHog", ConnectorCategory.ANALYTICS, "Product analytics", "PH"));
        when(connector.getSpec()).thenReturn(ConnectorSpec.apiKey(true, List.of(
                ConnectorConfigField.userInput("apiKey", "API Key", "hint", FieldType.SECRET, true))));
        when(connectorRegistry.getAll()).thenReturn(List.of(connector));
        when(connectorRegistry.capabilitiesOf(connector)).thenReturn(List.of(Capability.FETCH));

        Connection active = new Connection();
        active.setId("conn-active");
        active.setStatus("ACTIVE");
        Connection erroring = new Connection();
        erroring.setId("conn-error");
        erroring.setStatus("ERROR");
        when(connectionService.list(PROJECT_ID, "posthog")).thenReturn(List.of(active, erroring));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/catalog")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("posthog"))
                .andExpect(jsonPath("$[0].name").value("PostHog"))
                .andExpect(jsonPath("$[0].description").value("Product analytics"))
                .andExpect(jsonPath("$[0].category").value("ANALYTICS"))
                .andExpect(jsonPath("$[0].authType").value("API_KEY"))
                .andExpect(jsonPath("$[0].capabilities[0]").value("FETCH"))
                .andExpect(jsonPath("$[0].configFields[0].name").value("apiKey"))
                .andExpect(jsonPath("$[0].configFields[0].secret").value(true))
                .andExpect(jsonPath("$[0].connected").value(true))
                .andExpect(jsonPath("$[0].activeConnectionIds.length()").value(1))
                .andExpect(jsonPath("$[0].activeConnectionIds[0]").value("conn-active"));
    }

    @Test
    void listConnectorCatalog_noActiveConnections_isNotConnected() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "member-user-id")).thenReturn(true);

        Connector connector = mock(Connector.class);
        when(connector.getId()).thenReturn("posthog");
        when(connector.getMetadata()).thenReturn(new ConnectorMetadata(
                "posthog", "PostHog", ConnectorCategory.ANALYTICS, "Product analytics", "PH"));
        when(connector.getSpec()).thenReturn(ConnectorSpec.apiKey(true, List.of()));
        when(connectorRegistry.getAll()).thenReturn(List.of(connector));
        when(connectorRegistry.capabilitiesOf(connector)).thenReturn(List.of(Capability.FETCH));
        when(connectionService.list(PROJECT_ID, "posthog")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/catalog")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].connected").value(false))
                .andExpect(jsonPath("$[0].activeConnectionIds.length()").value(0));
    }
}
