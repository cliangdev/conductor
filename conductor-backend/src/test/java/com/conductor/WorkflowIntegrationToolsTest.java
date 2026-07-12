package com.conductor;

import com.conductor.config.SecurityConfig;
import com.conductor.controller.IntegrationController;
import com.conductor.entity.Connection;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.integration.Capability;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.TestFetchConnector;
import com.conductor.integration.connector.GcpBillingConnector;
import com.conductor.integration.connector.gsc.GscConnector;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.IntegrationFetchService;
import com.conductor.service.JwtService;
import com.conductor.service.OAuthFlowService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.IntegrationStepExecutor;
import com.conductor.workflow.StepExecutionContext;
import com.conductor.workflow.StepResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IntegrationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class WorkflowIntegrationToolsTest {

    private static final String PROJECT_ID = "proj-e2e";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private ConnectorRegistry connectorRegistry;
    @MockitoBean private ConnectionService connectionService;
    @MockitoBean private IntegrationFetchService fetchService;
    @MockitoBean private OAuthFlowService oAuthFlowService;
    @MockitoBean private ConnectionDataCacheRepository cacheRepository;
    @MockitoBean private WebhookEventRepository webhookEventRepository;
    @MockitoBean private ProjectSecurityService projectSecurityService;
    @MockitoBean private Optional<GcpBillingConnector> gcpBillingConnector;
    @MockitoBean private Optional<GscConnector> gscConnector;
    @MockitoBean private com.conductor.service.RuntimeTargetService runtimeTargetService;
    @MockitoBean private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;

    private final ObjectMapper realMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        User user = new User();
        user.setId("user-1");
        user.setEmail("test@example.com");
        user.setName("Test User");

        when(jwtService.validateToken("test-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("test-token")).thenReturn("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
    }

    // ── listIntegrationTools endpoint ─────────────────────────────────────────

    @Test
    void listIntegrationTools_returnsActiveConnectionsWithMetadata() throws Exception {
        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setConnectorId("test-data-source");
        conn.setDisplayLabel("Test Data Source");
        conn.setStatus("ACTIVE");

        Map<String, Object> toolMeta = Map.of(
            "description", "Test data source",
            "operations", List.of(Map.of("id", "fetch_test_data"))
        );

        when(connectionService.listForProject(PROJECT_ID)).thenReturn(List.of(conn));
        when(connectionService.computeToolMetadata(conn)).thenReturn(Optional.of(toolMeta));

        TestFetchConnector stub = new TestFetchConnector();
        when(connectorRegistry.getById("test-data-source")).thenReturn(Optional.of(stub));
        when(connectorRegistry.capabilitiesOf(stub)).thenReturn(List.of(Capability.FETCH));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/tools")
                .header("Authorization", "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].connectionId").value("conn-1"))
            .andExpect(jsonPath("$[0].connectorId").value("test-data-source"))
            .andExpect(jsonPath("$[0].capabilities[0]").value("FETCH"))
            .andExpect(jsonPath("$[0].toolMetadata.description").value("Test data source"));
    }

    @Test
    void listIntegrationTools_filtersOutConnectionsWithoutMetadata() throws Exception {
        Connection noMeta = new Connection();
        noMeta.setId("conn-2");
        noMeta.setConnectorId("github");
        noMeta.setStatus("ACTIVE");
        noMeta.setToolMetadata(null);

        when(connectionService.listForProject(PROJECT_ID)).thenReturn(List.of(noMeta));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/tools")
                .header("Authorization", "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listIntegrationTools_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/integrations/tools")
                .header("Authorization", "Bearer test-token"))
            .andExpect(status().isForbidden());
    }

    // ── IntegrationStepExecutor unit tests ───────────────────────────────────

    @Test
    void integrationStepExecutor_happyPath_returnsDataOutputs() throws Exception {
        ConnectionRepository connRepo = mock(ConnectionRepository.class);
        IntegrationFetchService fetchSvc = mock(IntegrationFetchService.class);

        Connection conn = new Connection();
        conn.setId("conn-gsc");
        conn.setConnectorId("gsc");
        conn.setStatus("ACTIVE");

        when(connRepo.findByProjectIdAndConnectorId("proj-1", "gsc")).thenReturn(List.of(conn));
        when(fetchSvc.fetchData("conn-gsc", true))
            .thenReturn(ConnectorData.healthy(Map.of("topQueries", List.of("q1", "q2"), "clicks", 100)));

        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        IntegrationStepExecutor executor = new IntegrationStepExecutor(connRepo, fetchSvc, realMapper, registry);

        StepExecutionContext ctx = mock(StepExecutionContext.class);
        when(ctx.getProjectId()).thenReturn("proj-1");
        when(ctx.getStepDefinition()).thenReturn(Map.of(
            "id", "seo",
            "uses", "integration",
            "with", Map.of("connector", "gsc")
        ));

        StepResult result = executor.execute(ctx);

        assertThat(result.getStatus()).isEqualTo(com.conductor.entity.WorkflowStepStatus.SUCCESS);
        assertThat(result.getOutputs()).containsKey("data");
        assertThat(result.getOutputs()).containsKey("clicks");
        assertThat(result.getOutputs().get("clicks")).isEqualTo("100");
    }

    @Test
    void integrationStepExecutor_degradedFetch_logsReasonAndExposesHealthOutput() throws Exception {
        ConnectionRepository connRepo = mock(ConnectionRepository.class);
        IntegrationFetchService fetchSvc = mock(IntegrationFetchService.class);

        Connection conn = new Connection();
        conn.setId("conn-gsc");
        conn.setConnectorId("gsc");
        conn.setStatus("ACTIVE");

        when(connRepo.findByProjectIdAndConnectorId("proj-1", "gsc")).thenReturn(List.of(conn));
        when(fetchSvc.fetchData("conn-gsc", true))
            .thenReturn(ConnectorData.degraded("Fetch failed: 401 invalid_grant — token expired", Map.of()));

        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        IntegrationStepExecutor executor = new IntegrationStepExecutor(connRepo, fetchSvc, realMapper, registry);

        StepExecutionContext ctx = mock(StepExecutionContext.class);
        when(ctx.getProjectId()).thenReturn("proj-1");
        when(ctx.getStepDefinition()).thenReturn(Map.of(
            "id", "seo",
            "uses", "integration",
            "with", Map.of("connector", "gsc")
        ));

        StepResult result = executor.execute(ctx);

        assertThat(result.getStatus()).isEqualTo(com.conductor.entity.WorkflowStepStatus.SUCCESS);
        assertThat(result.getLog())
            .contains("DEGRADED: Fetch failed: 401 invalid_grant — token expired")
            .contains("serving data fetched at");
        assertThat(result.getOutputs().get("health")).isEqualTo("DEGRADED");
    }

    @Test
    void integrationStepExecutor_healthyFetch_exposesHealthOutput_withBareStatusLine() throws Exception {
        ConnectionRepository connRepo = mock(ConnectionRepository.class);
        IntegrationFetchService fetchSvc = mock(IntegrationFetchService.class);

        Connection conn = new Connection();
        conn.setId("conn-gsc");
        conn.setConnectorId("gsc");
        conn.setStatus("ACTIVE");

        when(connRepo.findByProjectIdAndConnectorId("proj-1", "gsc")).thenReturn(List.of(conn));
        when(fetchSvc.fetchData("conn-gsc", true))
            .thenReturn(ConnectorData.healthy(Map.of("clicks", 1)));

        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        IntegrationStepExecutor executor = new IntegrationStepExecutor(connRepo, fetchSvc, realMapper, registry);

        StepExecutionContext ctx = mock(StepExecutionContext.class);
        when(ctx.getProjectId()).thenReturn("proj-1");
        when(ctx.getStepDefinition()).thenReturn(Map.of(
            "id", "seo",
            "uses", "integration",
            "with", Map.of("connector", "gsc")
        ));

        StepResult result = executor.execute(ctx);

        assertThat(result.getLog()).contains("← HEALTHY").doesNotContain("serving data fetched at");
        assertThat(result.getOutputs().get("health")).isEqualTo("HEALTHY");
    }

    @Test
    void integrationStepExecutor_missingConnector_fails() {
        ConnectionRepository connRepo = mock(ConnectionRepository.class);
        IntegrationFetchService fetchSvc = mock(IntegrationFetchService.class);

        when(connRepo.findByProjectIdAndConnectorId(anyString(), anyString())).thenReturn(List.of());

        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        IntegrationStepExecutor executor = new IntegrationStepExecutor(connRepo, fetchSvc, realMapper, registry);

        StepExecutionContext ctx = mock(StepExecutionContext.class);
        when(ctx.getProjectId()).thenReturn("proj-1");
        when(ctx.getStepDefinition()).thenReturn(Map.of(
            "with", Map.of("connector", "gsc")
        ));

        StepResult result = executor.execute(ctx);

        assertThat(result.getStatus()).isEqualTo(com.conductor.entity.WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("gsc");
    }

    @Test
    void integrationStepExecutor_missingWithBlock_fails() {
        ConnectionRepository connRepo = mock(ConnectionRepository.class);
        IntegrationFetchService fetchSvc = mock(IntegrationFetchService.class);

        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        IntegrationStepExecutor executor = new IntegrationStepExecutor(connRepo, fetchSvc, realMapper, registry);

        StepExecutionContext ctx = mock(StepExecutionContext.class);
        when(ctx.getProjectId()).thenReturn("proj-1");
        when(ctx.getStepDefinition()).thenReturn(Map.of("id", "step1", "uses", "integration"));

        StepResult result = executor.execute(ctx);

        assertThat(result.getStatus()).isEqualTo(com.conductor.entity.WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("'with' block");
    }

    @Test
    void integrationStepExecutor_setupRequired_fails() {
        ConnectionRepository connRepo = mock(ConnectionRepository.class);
        IntegrationFetchService fetchSvc = mock(IntegrationFetchService.class);

        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setConnectorId("gsc");
        conn.setStatus("ACTIVE");

        when(connRepo.findByProjectIdAndConnectorId("proj-1", "gsc")).thenReturn(List.of(conn));
        when(fetchSvc.fetchData("conn-1", true))
            .thenReturn(ConnectorData.setupRequired("Configure your Search Console property"));

        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        IntegrationStepExecutor executor = new IntegrationStepExecutor(connRepo, fetchSvc, realMapper, registry);

        StepExecutionContext ctx = mock(StepExecutionContext.class);
        when(ctx.getProjectId()).thenReturn("proj-1");
        when(ctx.getStepDefinition()).thenReturn(Map.of("with", Map.of("connector", "gsc")));

        StepResult result = executor.execute(ctx);

        assertThat(result.getStatus()).isEqualTo(com.conductor.entity.WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).isEqualTo("Configure your Search Console property");
    }
}
