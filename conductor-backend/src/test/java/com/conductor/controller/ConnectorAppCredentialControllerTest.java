package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.Connection;
import com.conductor.entity.ConnectorAppCredential;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.OAuth2Connector;
import com.conductor.integration.connector.GcpBillingConnector;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.repository.ConnectorAppCredentialRepository;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.CredentialService;
import com.conductor.service.ConnectorAppCredentialService;
import com.conductor.service.ConnectorAppCredentialVerificationService;
import com.conductor.service.ConnectorAppCredentialVerificationService.ReportStatus;
import com.conductor.service.ConnectorAppCredentialVerificationService.VerificationReport;
import com.conductor.service.IntegrationFetchService;
import com.conductor.service.JwtService;
import com.conductor.service.OAuthFlowService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.RuntimeTargetService;
import com.conductor.verification.Check;
import com.conductor.verification.CheckStatus;
import com.conductor.workflow.RunTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The connector app-credential surface: readiness on the catalog, the ADMIN-only write endpoints, and
 * the Verify action. The real {@link ConnectorAppCredentialService} is wired in over a stub repository
 * so the ADMIN rule and the env-var fallback are exercised for real rather than mocked away; only the
 * probe itself (a live provider call) is stubbed.
 */
@WebMvcTest(IntegrationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class,
        ConnectorAppCredentialService.class})
@TestPropertySource(properties = {"META_APP_ID=env-meta-app-id", "META_APP_SECRET=env-meta-secret-8888"})
class ConnectorAppCredentialControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String BASE = "/api/v1/projects/" + PROJECT_ID + "/integrations/";
    private static final String ADMIN_TOKEN = "admin-token";
    private static final String CREATOR_TOKEN = "creator-token";
    private static final String PROJECT_SECRET = "brought-my-own-secret-4242";

    @Autowired
    private MockMvc mockMvc;

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
    @MockitoBean private ConnectorAppCredentialVerificationService verificationService;
    @MockitoBean private ConnectorAppCredentialRepository appCredentialRepository;
    /** The app-credential envelope. Nothing here reads a stored secret back -- the last-4 preview
     *  comes from the row's own column -- so the crypto itself stays out of this slice. */
    @MockitoBean private CredentialService credentialService;
    /** The controller's own mapper (connection-config parsing only) — response JSON is the framework's. */
    @MockitoBean private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

    /** Stands in for the connector_app_credential table so PUT/GET/DELETE compose like they would in prod. */
    private final Map<String, ConnectorAppCredential> rows = new HashMap<>();

    private final StubOAuth2Connector meta =
            new StubOAuth2Connector("meta", "Meta", "META_APP_ID", "META_APP_SECRET");
    private final StubOAuth2Connector tiktok =
            new StubOAuth2Connector("tiktok", "TikTok", "TIKTOK_CLIENT_KEY", "TIKTOK_CLIENT_SECRET");
    private final StubConnector posthog = new StubConnector("posthog", "PostHog");

    @BeforeEach
    void setUp() {
        rows.clear();
        signIn(ADMIN_TOKEN, "admin-user", MemberRole.ADMIN);
        signIn(CREATOR_TOKEN, "creator-user", MemberRole.CREATOR);

        when(connectorRegistry.getAll()).thenReturn(List.of(meta, tiktok, posthog));
        when(connectorRegistry.capabilitiesOf(any())).thenReturn(List.of());
        when(connectorRegistry.findOAuth2("meta")).thenReturn(Optional.of(meta));
        when(connectorRegistry.findOAuth2("tiktok")).thenReturn(Optional.of(tiktok));
        when(connectorRegistry.findOAuth2("posthog")).thenReturn(Optional.empty());
        when(connectorRegistry.findOAuth2("nope")).thenReturn(Optional.empty());
        when(connectionService.list(eq(PROJECT_ID), anyString())).thenReturn(List.<Connection>of());

        // Stands in for the envelope: stamps the row's key reference the way a real DEK wrap would,
        // so a stored row is indistinguishable from a production one for these assertions.
        when(credentialService.encryptSecret(any(), anyString())).thenAnswer(i -> {
            i.getArgument(0, com.conductor.entity.EnvelopeEncrypted.class).setKmsKeyReference("wrapped-dek");
            return "enc:" + i.getArgument(1);
        });
        when(credentialService.decryptSecret(any(), anyString()))
                .thenAnswer(i -> i.getArgument(1, String.class).substring("enc:".length()));

        when(appCredentialRepository.findByProjectIdAndConnectorId(eq(PROJECT_ID), anyString()))
                .thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(1, String.class))));
        when(appCredentialRepository.findByProjectId(PROJECT_ID))
                .thenAnswer(i -> List.copyOf(rows.values()));
        when(appCredentialRepository.save(any(ConnectorAppCredential.class))).thenAnswer(i -> {
            ConnectorAppCredential row = i.getArgument(0);
            row.setUpdatedAt(OffsetDateTime.now());
            rows.put(row.getConnectorId(), row);
            return row;
        });
        org.mockito.Mockito.doAnswer(i -> rows.remove(
                        i.getArgument(0, ConnectorAppCredential.class).getConnectorId()))
                .when(appCredentialRepository).delete(any(ConnectorAppCredential.class));
    }

    // ---- catalog readiness ----

    @Test
    void catalogReportsNoneAndNamesTheMissingEnvironmentVariables() throws Exception {
        mockMvc.perform(get(BASE + "catalog").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].id").value("tiktok"))
                .andExpect(jsonPath("$[1].appCredential.credentialSource").value("NONE"))
                .andExpect(jsonPath("$[1].appCredential.configured").value(false))
                .andExpect(jsonPath("$[1].appCredential.missingProperties[0]").value("TIKTOK_CLIENT_KEY"))
                .andExpect(jsonPath("$[1].appCredential.missingProperties[1]").value("TIKTOK_CLIENT_SECRET"));
    }

    @Test
    void catalogReportsDeploymentWhenTheEnvironmentVariablesAreSet() throws Exception {
        mockMvc.perform(get(BASE + "catalog").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("meta"))
                .andExpect(jsonPath("$[0].appCredential.credentialSource").value("DEPLOYMENT"))
                .andExpect(jsonPath("$[0].appCredential.configured").value(true))
                .andExpect(jsonPath("$[0].appCredential.clientId").value("env-meta-app-id"))
                .andExpect(jsonPath("$[0].appCredential.clientSecretLast4").value("8888"))
                .andExpect(jsonPath("$[0].appCredential.missingProperties").isEmpty());
    }

    @Test
    void catalogReportsProjectWhenTheWorkspaceBroughtItsOwnApp() throws Exception {
        storeRow("meta", "workspace-app-id", PROJECT_SECRET);

        mockMvc.perform(get(BASE + "catalog").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].appCredential.credentialSource").value("PROJECT"))
                .andExpect(jsonPath("$[0].appCredential.clientId").value("workspace-app-id"))
                .andExpect(jsonPath("$[0].appCredential.clientSecretLast4").value("4242"))
                .andExpect(jsonPath("$[0].appCredential.updatedBy").value("admin-user"));
    }

    @Test
    void catalogLeavesNonOAuth2ConnectorsUntouched() throws Exception {
        mockMvc.perform(get(BASE + "catalog").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].id").value("posthog"))
                .andExpect(jsonPath("$[2].appCredential").doesNotExist());
    }

    @Test
    void catalogUsesOneCredentialQueryRegardlessOfConnectorCount() throws Exception {
        mockMvc.perform(get(BASE + "catalog").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk());

        verify(appCredentialRepository, times(1)).findByProjectId(PROJECT_ID);
        verify(appCredentialRepository, never()).findByProjectIdAndConnectorId(anyString(), anyString());
    }

    // ---- write endpoints ----

    @Test
    void putThenGetReturnsOnlyTheLastFourCharactersOfTheSecret() throws Exception {
        mockMvc.perform(putCredential(ADMIN_TOKEN, "meta", "workspace-app-id", PROJECT_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialSource").value("PROJECT"))
                .andExpect(jsonPath("$.clientSecretLast4").value("4242"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());

        mockMvc.perform(get(BASE + "meta/app-credentials").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialSource").value("PROJECT"))
                .andExpect(jsonPath("$.clientId").value("workspace-app-id"))
                .andExpect(jsonPath("$.clientSecretLast4").value("4242"));
    }

    @Test
    void deleteRestoresTheDeploymentFallback() throws Exception {
        storeRow("meta", "workspace-app-id", PROJECT_SECRET);

        mockMvc.perform(delete(BASE + "meta/app-credentials").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialSource").value("DEPLOYMENT"))
                .andExpect(jsonPath("$.clientId").value("env-meta-app-id"))
                .andExpect(jsonPath("$.clientSecretLast4").value("8888"));

        assertThat(rows).isEmpty();
    }

    @Test
    void aCreatorCannotSetAppCredentials() throws Exception {
        mockMvc.perform(putCredential(CREATOR_TOKEN, "meta", "workspace-app-id", PROJECT_SECRET))
                .andExpect(status().isForbidden());

        assertThat(rows).isEmpty();
    }

    @Test
    void aCreatorCannotClearAppCredentials() throws Exception {
        storeRow("meta", "workspace-app-id", PROJECT_SECRET);

        mockMvc.perform(delete(BASE + "meta/app-credentials").header("Authorization", "Bearer " + CREATOR_TOKEN))
                .andExpect(status().isForbidden());

        assertThat(rows).containsKey("meta");
    }

    @Test
    void anUnknownConnectorIdIsNotFoundRatherThanAServerError() throws Exception {
        mockMvc.perform(get(BASE + "nope/app-credentials").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(putCredential(ADMIN_TOKEN, "nope", "id", "secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aNonOAuth2ConnectorIdIsNotFoundRatherThanAServerError() throws Exception {
        mockMvc.perform(get(BASE + "posthog/app-credentials").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(BASE + "posthog/app-credentials/verify")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNotFound());
    }

    // ---- verify ----

    @Test
    void verifyReportsSuccessForAValidPair() throws Exception {
        when(verificationService.verify(PROJECT_ID, meta)).thenReturn(new VerificationReport(
                "meta", ReportStatus.VERIFIED, OffsetDateTime.now(),
                List.of(new Check("oauth-app-credentials", CheckStatus.PASS, "Meta issued an app access token"))));

        mockMvc.perform(post(BASE + "meta/app-credentials/verify")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectorId").value("meta"))
                .andExpect(jsonPath("$.status").value("verified"))
                .andExpect(jsonPath("$.checks[0].status").value("pass"));
    }

    @Test
    void verifyReportsFailureNamingTheProvidersReason() throws Exception {
        when(verificationService.verify(PROJECT_ID, meta)).thenReturn(new VerificationReport(
                "meta", ReportStatus.ERROR, OffsetDateTime.now(),
                List.of(new Check("oauth-app-credentials", CheckStatus.FAIL,
                        "Meta rejected the app credentials: Error validating client secret."))));

        mockMvc.perform(post(BASE + "meta/app-credentials/verify")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.checks[0].message").value(
                        org.hamcrest.Matchers.containsString("Error validating client secret.")));
    }

    @Test
    void verifyReportsCouldNotDetermineSeparatelyFromFailure() throws Exception {
        when(verificationService.verify(PROJECT_ID, meta)).thenReturn(new VerificationReport(
                "meta", ReportStatus.UNKNOWN, OffsetDateTime.now(),
                List.of(new Check("oauth-app-credentials", CheckStatus.WARN,
                        "Could not reach Meta (network error or timeout)"))));

        mockMvc.perform(post(BASE + "meta/app-credentials/verify")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unknown"))
                .andExpect(jsonPath("$.checks[0].status").value("warn"));
    }

    @Test
    void aCreatorMayVerifyButAReviewerMayNot() throws Exception {
        signIn("reviewer-token", "reviewer-user", MemberRole.REVIEWER);
        when(verificationService.verify(PROJECT_ID, meta)).thenReturn(new VerificationReport(
                "meta", ReportStatus.VERIFIED, OffsetDateTime.now(), List.of()));

        mockMvc.perform(post(BASE + "meta/app-credentials/verify")
                        .header("Authorization", "Bearer " + CREATOR_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "meta/app-credentials/verify")
                        .header("Authorization", "Bearer reviewer-token"))
                .andExpect(status().isForbidden());
    }

    // ---- the secret never leaves ----

    @Test
    void noResponseBodyContainsTheClientSecret() throws Exception {
        String put = mockMvc.perform(putCredential(ADMIN_TOKEN, "meta", "workspace-app-id", PROJECT_SECRET))
                .andReturn().getResponse().getContentAsString();
        String read = mockMvc.perform(get(BASE + "meta/app-credentials")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andReturn().getResponse().getContentAsString();
        String catalog = mockMvc.perform(get(BASE + "catalog")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andReturn().getResponse().getContentAsString();
        String cleared = mockMvc.perform(delete(BASE + "meta/app-credentials")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andReturn().getResponse().getContentAsString();

        assertThat(put).doesNotContain(PROJECT_SECRET);
        assertThat(read).doesNotContain(PROJECT_SECRET);
        assertThat(catalog).doesNotContain(PROJECT_SECRET).doesNotContain("env-meta-secret-8888");
        assertThat(cleared).doesNotContain("env-meta-secret-8888");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putCredential(
            String token, String connectorId, String clientId, String clientSecret) {
        return put(BASE + connectorId + "/app-credentials")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"" + clientId + "\",\"clientSecret\":\"" + clientSecret + "\"}");
    }

    private void storeRow(String connectorId, String clientId, String clientSecret) {
        ConnectorAppCredential row = new ConnectorAppCredential();
        row.setId(connectorId + "-row");
        row.setProjectId(PROJECT_ID);
        row.setConnectorId(connectorId);
        row.setClientId(clientId);
        row.setClientSecretEncrypted("enc:" + clientSecret);
        row.setKmsKeyReference("wrapped-dek");
        row.setClientSecretLast4(clientSecret.substring(clientSecret.length() - 4));
        row.setUpdatedBy("admin-user");
        row.setUpdatedAt(OffsetDateTime.now());
        rows.put(connectorId, row);
    }

    private void signIn(String token, String userId, MemberRole role) {
        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@example.com");
        user.setName(userId);
        when(jwtService.validateToken(token)).thenReturn(true);
        when(jwtService.getUserIdFromToken(token)).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, userId)).thenReturn(true);
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, userId))
                .thenReturn(Optional.of(member));
    }

    /** An OAuth2 connector reduced to the hooks the credential surface reads. */
    private record StubOAuth2Connector(String id, String name, String clientIdProperty,
                                       String clientSecretProperty) implements OAuth2Connector {
        @Override
        public String getId() {
            return id;
        }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata(id, name, ConnectorCategory.MARKETING, name + " connector", name);
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(true, List.of());
        }

        @Override
        public List<String> oauthScopes() {
            return List.of();
        }

        @Override
        public String clientIdProperty() {
            return clientIdProperty;
        }

        @Override
        public String clientSecretProperty() {
            return clientSecretProperty;
        }
    }

    /** A connector with no OAuth2 at all — it must gain no credential fields anywhere. */
    private record StubConnector(String id, String name) implements Connector {
        @Override
        public String getId() {
            return id;
        }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata(id, name, ConnectorCategory.ANALYTICS, name + " connector", name);
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.apiKey(true, List.of());
        }
    }
}
