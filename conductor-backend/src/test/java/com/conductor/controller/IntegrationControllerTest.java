package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectApiKey;
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
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.IntegrationToolSpec;
import com.conductor.integration.connector.GcpBillingConnector;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
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
import com.conductor.repository.ProjectMemberRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IntegrationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
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
    @MockitoBean private ProjectMemberRepository projectMemberRepository;
    @MockitoBean private ConnectorFeedRepository connectorFeedRepository;
    @MockitoBean private GcpBillingConnector gcpBillingConnector;
    @MockitoBean private RuntimeTargetService runtimeTargetService;
    @MockitoBean private com.conductor.service.ConnectorAppCredentialService appCredentialService;
    @MockitoBean private com.conductor.service.ConnectorAppCredentialVerificationService appCredentialVerificationService;
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
    void listConnections_projectApiKey_succeedsAsMemberLevel() throws Exception {
        when(connectionService.list(PROJECT_ID, GCP_CONNECTOR_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/" + GCP_CONNECTOR_ID + "/connections")
                        .header("Authorization", "Bearer project-api-key"))
                .andExpect(status().isOk());
    }

    @Test
    void createConnection_projectApiKey_returnsClean403NotServerError() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + GCP_SA_CONNECTOR_ID + "/connections")
                        .header("Authorization", "Bearer project-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceAccountKey\":\"{}\"}"))
                .andExpect(status().isForbidden());
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
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
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
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/gcp-billing/gcp-projects")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listGcpProjects_noOAuthCredentials_isRejected_andDoesNotCallConnector() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(connectionService.findSingle(PROJECT_ID, GCP_CONNECTOR_ID)).thenReturn(Optional.empty());

        // Missing OAuth credentials raises a CONFLICT ResponseStatusException; the application's
        // Missing OAuth credentials raise a CONFLICT ResponseStatusException, which now surfaces as its
        // own 409 — GlobalExceptionHandler handles ErrorResponseException rather than letting the
        // catch-all render every deliberate 4xx as a 500. The contract under test is unchanged: the
        // request is rejected and the connector is never invoked.
        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/gcp-billing/gcp-projects")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isConflict());
        org.mockito.Mockito.verifyNoInteractions(gcpBillingConnector);
    }

    @Test
    void listGcpProjects_refreshesExpiringToken() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
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
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
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
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);

        // Invalid gcpProjectId raises a BAD_REQUEST ResponseStatusException, which now surfaces as its
        // own 400. Contract: rejected, and the connector is not called.
        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/gcp-billing/bq-datasets")
                        .param("gcpProjectId", "bad id!")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(gcpBillingConnector);
    }

    @Test
    void listBqDatasets_nonMember_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(false);

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
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
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
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
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
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
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
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
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
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listIntegrations_member_returns200() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        when(connectorRegistry.getAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk());
    }

    // ---- listConnectorCatalog ----

    @Test
    void listConnectorCatalog_nonMember_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/catalog")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listConnectorCatalog_member_returnsAllRegisteredConnectors_withActiveConnectionState() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);

        Connector connector = mock(Connector.class);
        when(connector.getId()).thenReturn("posthog");
        when(connector.getMetadata()).thenReturn(new ConnectorMetadata(
                "posthog", "PostHog", ConnectorCategory.ANALYTICS, "Product analytics", "PH"));
        when(connector.getSpec()).thenReturn(ConnectorSpec.apiKey(true, List.of(
                ConnectorConfigField.userInput("apiKey", "API Key", "hint", FieldType.SECRET, true))));
        when(connector.getToolSpec()).thenReturn(new IntegrationToolSpec("Product analytics", List.of()));
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
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);

        Connector connector = mock(Connector.class);
        when(connector.getId()).thenReturn("posthog");
        when(connector.getMetadata()).thenReturn(new ConnectorMetadata(
                "posthog", "PostHog", ConnectorCategory.ANALYTICS, "Product analytics", "PH"));
        when(connector.getSpec()).thenReturn(ConnectorSpec.apiKey(true, List.of()));
        when(connector.getToolSpec()).thenReturn(new IntegrationToolSpec("Product analytics", List.of()));
        when(connectorRegistry.getAll()).thenReturn(List.of(connector));
        when(connectorRegistry.capabilitiesOf(connector)).thenReturn(List.of(Capability.FETCH));
        when(connectionService.list(PROJECT_ID, "posthog")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/catalog")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].connected").value(false))
                .andExpect(jsonPath("$[0].activeConnectionIds.length()").value(0));
    }

    /** A membership row carrying {@code role} — what the real ProjectSecurityService reads. */
    private static ProjectMember memberWithRole(MemberRole role) {
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        return member;
    }

    // ---- connector feeds ----

    private static final String FEED_CONNECTOR_ID = "posthog";

    private ConnectorFeed activeFeed() {
        ConnectorFeed feed = new ConnectorFeed();
        feed.setId("feed-1");
        feed.setProjectId(PROJECT_ID);
        feed.setConnectionId("conn-1");
        feed.setConnectorId(FEED_CONNECTOR_ID);
        feed.setIngestId("weekly-mrr");
        feed.setEnabled(true);
        feed.setIntervalMinutes(1440);
        feed.setStatus(ConnectorFeedStatus.ACTIVE);
        feed.setConsecutiveFailures(0);
        feed.setNextRunAt(java.time.OffsetDateTime.now());
        return feed;
    }

    private void stubIngestSpec() {
        Connector connector = mock(Connector.class);
        IngestSpec spec = new IngestSpec("weekly-mrr", "Weekly MRR", "Weekly revenue snapshot",
                IngestMode.SNAPSHOT, null, null, 1440, null, null, null,
                new com.conductor.integration.DigestSpec("trend", "date", List.of(), List.of(), "metrics/mrr", 13));
        when(connector.getToolSpec()).thenReturn(new IntegrationToolSpec("PostHog", List.of(), List.of(), List.of(spec)));
        when(connectorRegistry.getById(FEED_CONNECTOR_ID)).thenReturn(Optional.of(connector));
    }

    @Test
    void listConnectorFeeds_nonMember_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/" + FEED_CONNECTOR_ID + "/feeds")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listConnectorFeeds_member_returnsFeedsEnrichedWithIngestSpecLabel() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "member-user-id")).thenReturn(true);
        stubIngestSpec();
        when(connectorFeedRepository.findByProjectIdAndConnectorId(PROJECT_ID, FEED_CONNECTOR_ID))
                .thenReturn(List.of(activeFeed()));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/" + FEED_CONNECTOR_ID + "/feeds")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("feed-1"))
                .andExpect(jsonPath("$[0].ingestId").value("weekly-mrr"))
                .andExpect(jsonPath("$[0].label").value("Weekly MRR"))
                .andExpect(jsonPath("$[0].description").value("Weekly revenue snapshot"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].intervalMinutes").value(1440))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].isMetricFeed").value(true));
    }

    // ---- updateConnectorFeed ----

    @Test
    void updateConnectorFeed_nonAdminOrCreator_returns403() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.REVIEWER)));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(connectorFeedRepository);
    }

    @Test
    void updateConnectorFeed_machinePrincipal_isRejectedStructurally() throws Exception {
        // A project API key resolves to a ProjectScopedPrincipal, not a User -- requireAdminOrCreator
        // must reject it before ever touching the repository, per the "!(principal instanceof User)"
        // idiom this controller already uses for writes.
        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1")
                        .header("Authorization", "Bearer project-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(connectorFeedRepository);
    }

    @Test
    void updateConnectorFeed_happyPath_updatesEnabledAndInterval() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        stubIngestSpec();
        ConnectorFeed feed = activeFeed();
        when(connectorFeedRepository.findById("feed-1")).thenReturn(Optional.of(feed));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"intervalMinutes\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.intervalMinutes").value(60));

        assertThat(feed.isEnabled()).isFalse();
        assertThat(feed.getIntervalMinutes()).isEqualTo(60);
        verify(connectorFeedRepository).save(feed);
    }

    @Test
    void updateConnectorFeed_intervalOutOfRange_returns400() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalMinutes\":0}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(connectorFeedRepository);
    }

    @Test
    void updateConnectorFeed_wrongProject_returns404() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        ConnectorFeed feed = activeFeed();
        feed.setProjectId("some-other-project");
        when(connectorFeedRepository.findById("feed-1")).thenReturn(Optional.of(feed));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
        verify(connectorFeedRepository, never()).save(any());
    }

    // ---- runConnectorFeedNow ----

    @Test
    void runConnectorFeedNow_happyPath_returns202AndReduesFeed() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        stubIngestSpec();
        ConnectorFeed feed = activeFeed();
        java.time.OffsetDateTime originalNextRun = feed.getNextRunAt();
        when(connectorFeedRepository.findById("feed-1")).thenReturn(Optional.of(feed));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1/runs")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("feed-1"));

        assertThat(feed.getNextRunAt()).isBeforeOrEqualTo(java.time.OffsetDateTime.now());
        assertThat(feed.getNextRunAt()).isNotEqualTo(originalNextRun);
        verify(connectorFeedRepository).save(feed);
    }

    @Test
    void runConnectorFeedNow_machinePrincipal_isRejectedStructurally() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1/runs")
                        .header("Authorization", "Bearer project-api-key"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(connectorFeedRepository);
    }

    @Test
    void runConnectorFeedNow_nonAdminOrCreator_returns403() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.REVIEWER)));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1/runs")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(connectorFeedRepository);
    }

    // ---- resumeFeed (updateConnectorFeed enabled:true / runConnectorFeedNow) ----

    private ConnectorFeed feedWithStatus(ConnectorFeedStatus status) {
        ConnectorFeed feed = activeFeed();
        feed.setEnabled(false);
        feed.setStatus(status);
        feed.setConsecutiveFailures(8);
        feed.setLastError("previous failure");
        return feed;
    }

    @Test
    void updateConnectorFeed_enabledTrueOnDeadFeed_resumesIt() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        stubIngestSpec();
        ConnectorFeed feed = feedWithStatus(ConnectorFeedStatus.DEAD);
        when(connectorFeedRepository.findById("feed-1")).thenReturn(Optional.of(feed));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ConnectorFeed> captor = org.mockito.ArgumentCaptor.forClass(ConnectorFeed.class);
        verify(connectorFeedRepository).save(captor.capture());
        ConnectorFeed saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ConnectorFeedStatus.ACTIVE);
        assertThat(saved.getConsecutiveFailures()).isZero();
        assertThat(saved.getLastError()).isNull();
    }

    @Test
    void updateConnectorFeed_enabledTrueOnSetupRequiredFeed_resumesIt() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        stubIngestSpec();
        ConnectorFeed feed = feedWithStatus(ConnectorFeedStatus.SETUP_REQUIRED);
        when(connectorFeedRepository.findById("feed-1")).thenReturn(Optional.of(feed));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ConnectorFeed> captor = org.mockito.ArgumentCaptor.forClass(ConnectorFeed.class);
        verify(connectorFeedRepository).save(captor.capture());
        ConnectorFeed saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ConnectorFeedStatus.ACTIVE);
        assertThat(saved.getConsecutiveFailures()).isZero();
        assertThat(saved.getLastError()).isNull();
    }

    @Test
    void updateConnectorFeed_enabledTrueOnPausedFeed_resumesIt() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        stubIngestSpec();
        ConnectorFeed feed = feedWithStatus(ConnectorFeedStatus.PAUSED);
        when(connectorFeedRepository.findById("feed-1")).thenReturn(Optional.of(feed));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ConnectorFeed> captor = org.mockito.ArgumentCaptor.forClass(ConnectorFeed.class);
        verify(connectorFeedRepository).save(captor.capture());
        ConnectorFeed saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ConnectorFeedStatus.ACTIVE);
        assertThat(saved.getConsecutiveFailures()).isZero();
        assertThat(saved.getLastError()).isNull();
    }

    @Test
    void updateConnectorFeed_enabledFalse_doesNotResume() throws Exception {
        // A user disabling a feed isn't asking to clear its failure state.
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        stubIngestSpec();
        ConnectorFeed feed = feedWithStatus(ConnectorFeedStatus.DEAD);
        feed.setEnabled(true);
        when(connectorFeedRepository.findById("feed-1")).thenReturn(Optional.of(feed));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        assertThat(feed.getStatus()).isEqualTo(ConnectorFeedStatus.DEAD);
        assertThat(feed.getConsecutiveFailures()).isEqualTo(8);
        assertThat(feed.getLastError()).isEqualTo("previous failure");
    }

    @Test
    void updateConnectorFeed_intervalOnly_doesNotResume() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        stubIngestSpec();
        ConnectorFeed feed = feedWithStatus(ConnectorFeedStatus.DEAD);
        when(connectorFeedRepository.findById("feed-1")).thenReturn(Optional.of(feed));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalMinutes\":60}"))
                .andExpect(status().isOk());

        assertThat(feed.getStatus()).isEqualTo(ConnectorFeedStatus.DEAD);
        assertThat(feed.getConsecutiveFailures()).isEqualTo(8);
        assertThat(feed.getLastError()).isEqualTo("previous failure");
        assertThat(feed.getIntervalMinutes()).isEqualTo(60);
    }

    @Test
    void runConnectorFeedNow_deadFeed_resumesAndReduesIt() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "member-user-id"))
                .thenReturn(Optional.of(memberWithRole(MemberRole.CREATOR)));
        stubIngestSpec();
        ConnectorFeed feed = feedWithStatus(ConnectorFeedStatus.DEAD);
        when(connectorFeedRepository.findById("feed-1")).thenReturn(Optional.of(feed));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/integrations/"
                        + FEED_CONNECTOR_ID + "/feeds/feed-1/runs")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isAccepted());

        org.mockito.ArgumentCaptor<ConnectorFeed> captor = org.mockito.ArgumentCaptor.forClass(ConnectorFeed.class);
        verify(connectorFeedRepository).save(captor.capture());
        ConnectorFeed saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ConnectorFeedStatus.ACTIVE);
        assertThat(saved.getConsecutiveFailures()).isZero();
        assertThat(saved.getLastError()).isNull();
        assertThat(saved.getNextRunAt()).isBeforeOrEqualTo(java.time.OffsetDateTime.now());
        assertThat(saved.getNextRunAt()).isAfter(java.time.OffsetDateTime.now().minusSeconds(30));
    }
}
