package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.ConnectorAppCredential;
import com.conductor.entity.IntegrationOAuthState;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.OAuth2Connector;
import com.conductor.repository.ConnectorAppCredentialRepository;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.conductor.service.ConnectorAppCredentialService.CredentialSource;
import com.conductor.service.ConnectorAppCredentialService.ResolvedAppCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.kms.v1.DecryptResponse;
import com.google.cloud.kms.v1.EncryptResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-project connector app credentials, with the deployment env var as fallback.
 *
 * <p>The load-bearing invariant is the negative one: a project that has stored nothing must resolve
 * byte-for-byte what the deployment resolved before this seam existed. The OAuth-threading section
 * at the bottom pins that against the real {@link OAuthFlowService}, since that is where a leaked or
 * dropped {@code projectId} would actually do damage.
 *
 * <p>The crypto here is the real {@link CredentialService} envelope, not a stub: the local-profile
 * implementation for most tests, and the KMS one over a fake KEK for the envelope section, so the
 * per-row DEK is exercised rather than described.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectorAppCredentialServiceTest {

    private static final String PROJECT_A = "proj-a";
    private static final String PROJECT_B = "proj-b";
    private static final String CONNECTOR_ID = "acme";
    private static final String WORKSPACE_ONLY_CONNECTOR_ID = "platform";
    private static final String REDIRECT_URI = "http://localhost:8080/api/v1/oauth/callback";
    private static final String LOCAL_ENCRYPTION_KEY = "test-encryption-key-for-unit-tests";
    /** Stand-in KEK for the fake KMS: reversible, and obviously not the DEK it wraps. */
    private static final byte FAKE_KEK_MASK = 0x5A;

    @Mock private ConnectorAppCredentialRepository repository;
    @Mock private Environment environment;
    @Mock private ProjectSecurityService projectSecurityService;
    @Mock private IntegrationOAuthStateRepository oAuthStateRepository;
    @Mock private ConnectionService connectionService;
    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private ConnectionHealthService connectionHealthService;
    @Mock private RestTemplate restTemplate;

    private CredentialService credentialService;
    private ConnectorAppCredentialService service;
    private final AcmeConnector connector = new AcmeConnector();
    private final WorkspaceOnlyConnector workspaceOnlyConnector = new WorkspaceOnlyConnector();

    @BeforeEach
    void setUp() {
        credentialService = new LocalCredentialService(new ObjectMapper(), LOCAL_ENCRYPTION_KEY);
        service = new ConnectorAppCredentialService(repository, credentialService, environment,
                projectSecurityService);
    }

    /** A non-Google OAuth2 connector, so the env property names under test are unmistakable. */
    private static final class AcmeConnector implements OAuth2Connector {
        @Override public String getId() { return CONNECTOR_ID; }
        @Override public List<String> oauthScopes() { return List.of("acme.read"); }
        @Override public String authorizationUrl() { return "https://acme.example.com/oauth/authorize"; }
        @Override public String tokenUrl() { return "https://acme.example.com/oauth/token"; }
        @Override public String clientIdProperty() { return "ACME_OAUTH_CLIENT_ID"; }
        @Override public String clientSecretProperty() { return "ACME_OAUTH_CLIENT_SECRET"; }
        @Override public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata(CONNECTOR_ID, "Acme", ConnectorCategory.ANALYTICS, "Acme", "AC");
        }
        @Override public ConnectorSpec getSpec() { return ConnectorSpec.oauth2(true, List.of()); }
    }

    /**
     * A connector whose app must belong to the workspace — the publishing platforms' shape. Identical
     * to {@link AcmeConnector} but for {@link OAuth2Connector#allowsDeploymentCredentials()}, so any
     * difference in resolution is attributable to that one flag.
     */
    private static final class WorkspaceOnlyConnector implements OAuth2Connector {
        @Override public String getId() { return WORKSPACE_ONLY_CONNECTOR_ID; }
        @Override public List<String> oauthScopes() { return List.of("platform.publish"); }
        @Override public String authorizationUrl() { return "https://platform.example.com/oauth/authorize"; }
        @Override public String tokenUrl() { return "https://platform.example.com/oauth/token"; }
        @Override public String clientIdProperty() { return "PLATFORM_APP_ID"; }
        @Override public String clientSecretProperty() { return "PLATFORM_APP_SECRET"; }
        @Override public boolean allowsDeploymentCredentials() { return false; }
        @Override public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata(WORKSPACE_ONLY_CONNECTOR_ID, "Platform", ConnectorCategory.MARKETING,
                    "Platform", "PL");
        }
        @Override public ConnectorSpec getSpec() { return ConnectorSpec.oauth2(false, List.of()); }
    }

    private void stubDeploymentEnv(String clientId, String clientSecret) {
        when(environment.getProperty("ACME_OAUTH_CLIENT_ID", "")).thenReturn(clientId);
        when(environment.getProperty("ACME_OAUTH_CLIENT_SECRET", "")).thenReturn(clientSecret);
    }

    private ConnectorAppCredential storedRow(String projectId, String clientId, String clientSecret) {
        ConnectorAppCredential row = envelopeRow(projectId, clientId, clientSecret);
        when(repository.findByProjectIdAndConnectorId(projectId, CONNECTOR_ID))
                .thenReturn(Optional.of(row));
        return row;
    }

    /** A row written the way {@link ConnectorAppCredentialService#put} writes one. */
    private ConnectorAppCredential envelopeRow(String projectId, String clientId, String clientSecret) {
        ConnectorAppCredential row = new ConnectorAppCredential();
        row.setId("cred-" + projectId);
        row.setProjectId(projectId);
        row.setConnectorId(CONNECTOR_ID);
        row.setClientId(clientId);
        row.setClientSecretEncrypted(credentialService.encryptSecret(row, clientSecret));
        row.setClientSecretLast4(clientSecret.substring(clientSecret.length() - 4));
        row.setUpdatedBy("user-1");
        row.setUpdatedAt(OffsetDateTime.now());
        return row;
    }

    private ConnectorAppCredential savedRow() {
        ArgumentCaptor<ConnectorAppCredential> captor = ArgumentCaptor.forClass(ConnectorAppCredential.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private User admin() {
        User user = new User();
        user.setId("user-1");
        user.setEmail("admin@example.com");
        when(projectSecurityService.isProjectAdmin(any(), eq("user-1"))).thenReturn(true);
        return user;
    }

    private User nonAdmin() {
        User user = new User();
        user.setId("user-2");
        user.setEmail("creator@example.com");
        when(projectSecurityService.isProjectAdmin(any(), eq("user-2"))).thenReturn(false);
        return user;
    }

    // -------------------------------------------------------------------------------------------
    // [auto] A project row overrides the env var for that project only
    // -------------------------------------------------------------------------------------------

    @Test
    void aProjectRowOverridesTheEnvVarForThatProjectOnly() {
        stubDeploymentEnv("deployment-client-id", "deployment-client-secret");
        storedRow(PROJECT_A, "project-a-client-id", "project-a-client-secret");

        ResolvedAppCredentials forA = service.resolve(PROJECT_A, connector);
        ResolvedAppCredentials forB = service.resolve(PROJECT_B, connector);

        assertThat(forA.source()).isEqualTo(CredentialSource.PROJECT);
        assertThat(forA.clientId()).isEqualTo("project-a-client-id");
        assertThat(forA.clientSecret()).isEqualTo("project-a-client-secret");

        assertThat(forB.source()).isEqualTo(CredentialSource.DEPLOYMENT);
        assertThat(forB.clientId()).isEqualTo("deployment-client-id");
        assertThat(forB.clientSecret()).isEqualTo("deployment-client-secret");
    }

    // -------------------------------------------------------------------------------------------
    // [auto] With no project row, resolution falls back to the env var
    // -------------------------------------------------------------------------------------------

    @Test
    void withNoProjectRowResolutionFallsBackToTheDeploymentEnvVars() {
        stubDeploymentEnv("deployment-client-id", "deployment-client-secret");

        ResolvedAppCredentials resolved = service.resolve(PROJECT_A, connector);

        assertThat(resolved.source()).isEqualTo(CredentialSource.DEPLOYMENT);
        assertThat(resolved.configured()).isTrue();
        assertThat(resolved.missingProperties()).isEmpty();
        assertThat(resolved.clientId()).isEqualTo("deployment-client-id");
        assertThat(resolved.clientSecret()).isEqualTo("deployment-client-secret");
    }

    // -------------------------------------------------------------------------------------------
    // [auto] With neither, resolution reports NONE plus the missing property names
    // -------------------------------------------------------------------------------------------

    @Test
    void withNeitherSourceResolutionReportsNoneAndNamesEveryMissingProperty() {
        stubDeploymentEnv("", "");

        ResolvedAppCredentials resolved = service.resolve(PROJECT_A, connector);

        assertThat(resolved.source()).isEqualTo(CredentialSource.NONE);
        assertThat(resolved.configured()).isFalse();
        assertThat(resolved.clientId()).isNull();
        assertThat(resolved.clientSecret()).isNull();
        assertThat(resolved.missingProperties())
                .containsExactly("ACME_OAUTH_CLIENT_ID", "ACME_OAUTH_CLIENT_SECRET");
    }

    @Test
    void aHalfConfiguredDeploymentNamesOnlyTheHalfThatIsMissing() {
        stubDeploymentEnv("deployment-client-id", "");

        ResolvedAppCredentials resolved = service.resolve(PROJECT_A, connector);

        assertThat(resolved.source()).isEqualTo(CredentialSource.NONE);
        assertThat(resolved.missingProperties()).containsExactly("ACME_OAUTH_CLIENT_SECRET");
    }

    // -------------------------------------------------------------------------------------------
    // [auto] The client secret is encrypted at rest and never returned in plaintext by a read path
    // -------------------------------------------------------------------------------------------

    @Test
    void theStoredSecretIsCiphertextOnTheRowAndNeverPlaintext() {
        service.put(PROJECT_A, CONNECTOR_ID, "project-a-client-id", "sup3r-secret-value", admin());

        ArgumentCaptor<ConnectorAppCredential> captor = ArgumentCaptor.forClass(ConnectorAppCredential.class);
        verify(repository).save(captor.capture());
        ConnectorAppCredential saved = captor.getValue();

        assertThat(saved.getClientSecretEncrypted()).isNotEqualTo("sup3r-secret-value");
        assertThat(saved.getClientSecretEncrypted()).doesNotContain("sup3r-secret-value");
        assertThat(credentialService.decryptSecret(saved, saved.getClientSecretEncrypted()))
                .isEqualTo("sup3r-secret-value");
        assertThat(saved.getClientId()).isEqualTo("project-a-client-id");
        assertThat(saved.getUpdatedBy()).isEqualTo("user-1");
    }

    @Test
    void statusMasksTheSecretToItsLastFourCharacters() {
        stubDeploymentEnv("deployment-client-id", "deployment-client-secret");
        storedRow(PROJECT_A, "project-a-client-id", "sup3r-secret-value");

        var status = service.status(PROJECT_A, connector);

        assertThat(status.source()).isEqualTo(CredentialSource.PROJECT);
        assertThat(status.clientId()).isEqualTo("project-a-client-id");
        assertThat(status.clientSecretLast4()).isEqualTo("alue");
        assertThat(status.updatedBy()).isEqualTo("user-1");
        assertThat(status.updatedAt()).isNotNull();
    }

    @Test
    void statusForAnUnconfiguredConnectorReportsNoneAndTheMissingProperties() {
        stubDeploymentEnv("", "");

        var status = service.status(PROJECT_A, connector);

        assertThat(status.configured()).isFalse();
        assertThat(status.source()).isEqualTo(CredentialSource.NONE);
        assertThat(status.clientSecretLast4()).isNull();
        assertThat(status.missingProperties())
                .containsExactly("ACME_OAUTH_CLIENT_ID", "ACME_OAUTH_CLIENT_SECRET");
    }

    @Test
    void statusesResolvesAWholeCatalogFromASingleQuery() {
        stubDeploymentEnv("deployment-client-id", "deployment-client-secret");
        ConnectorAppCredential row = envelopeRow(PROJECT_A, "project-a-client-id", "project-a-client-secret");
        when(repository.findByProjectId(PROJECT_A)).thenReturn(List.of(row));

        var statuses = service.statuses(PROJECT_A, List.of(connector));

        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).source()).isEqualTo(CredentialSource.PROJECT);
        assertThat(statuses.get(0).clientId()).isEqualTo("project-a-client-id");
        verify(repository, never()).findByProjectIdAndConnectorId(any(), any());
    }

    // -------------------------------------------------------------------------------------------
    // [auto] The secret rides the connection envelope: a per-row DEK, wrapped by the KMS KEK
    // -------------------------------------------------------------------------------------------

    /**
     * A KMS whose KEK is a byte mask. Reversible, so a wrapped DEK really does unwrap to the DEK that
     * was generated, and visibly not the DEK itself, so "wrapped" can be asserted rather than assumed.
     */
    private static KeyManagementServiceClient fakeKms() {
        KeyManagementServiceClient kms = mock(KeyManagementServiceClient.class);
        when(kms.encrypt(anyString(), any(ByteString.class))).thenAnswer(call ->
                EncryptResponse.newBuilder().setCiphertext(maskedWithFakeKek(call.getArgument(1))).build());
        when(kms.decrypt(anyString(), any(ByteString.class))).thenAnswer(call ->
                DecryptResponse.newBuilder().setPlaintext(maskedWithFakeKek(call.getArgument(1))).build());
        return kms;
    }

    private static ByteString maskedWithFakeKek(ByteString in) {
        byte[] bytes = in.toByteArray();
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= FAKE_KEK_MASK;
        }
        return ByteString.copyFrom(bytes);
    }

    private ConnectorAppCredentialService kmsBackedService(CredentialService envelope) {
        return new ConnectorAppCredentialService(repository, envelope, environment, projectSecurityService);
    }

    private static CredentialService kmsEnvelope() {
        return new GcpKmsCredentialService(new ObjectMapper(), fakeKms(), "gcp-project", "global",
                "conductor-secrets", "integration-credentials-kek");
    }

    @Test
    void theStoredSecretRoundTripsThroughTheKmsEnvelope() {
        ConnectorAppCredentialService kmsBacked = kmsBackedService(kmsEnvelope());

        kmsBacked.put(PROJECT_A, CONNECTOR_ID, "project-a-client-id", "sup3r-secret-value", admin());
        ConnectorAppCredential saved = savedRow();

        assertThat(saved.getKmsKeyReference()).isNotBlank();
        assertThat(saved.getClientSecretEncrypted()).doesNotContain("sup3r-secret-value");

        when(repository.findByProjectIdAndConnectorId(PROJECT_A, CONNECTOR_ID)).thenReturn(Optional.of(saved));
        assertThat(kmsBacked.resolve(PROJECT_A, connector).clientSecret()).isEqualTo("sup3r-secret-value");
    }

    @Test
    void eachCredentialRowIsEncryptedUnderItsOwnDek() {
        ConnectorAppCredentialService kmsBacked = kmsBackedService(kmsEnvelope());

        kmsBacked.put(PROJECT_A, CONNECTOR_ID, "client-a", "identical-secret", admin());
        kmsBacked.put(PROJECT_B, CONNECTOR_ID, "client-b", "identical-secret", admin());

        ArgumentCaptor<ConnectorAppCredential> captor = ArgumentCaptor.forClass(ConnectorAppCredential.class);
        verify(repository, times(2)).save(captor.capture());
        ConnectorAppCredential rowA = captor.getAllValues().get(0);
        ConnectorAppCredential rowB = captor.getAllValues().get(1);

        assertThat(rowA.getKmsKeyReference()).isNotBlank();
        assertThat(rowB.getKmsKeyReference()).isNotBlank();
        assertThat(rowA.getKmsKeyReference()).isNotEqualTo(rowB.getKmsKeyReference());
        // Same plaintext, different DEKs -- so the ciphertexts cannot match either.
        assertThat(rowA.getClientSecretEncrypted()).isNotEqualTo(rowB.getClientSecretEncrypted());
    }

    @Test
    void theLocalProfileEncryptsWithNoKmsConfigured() {
        service.put(PROJECT_A, CONNECTOR_ID, "project-a-client-id", "sup3r-secret-value", admin());
        ConnectorAppCredential saved = savedRow();

        assertThat(saved.getKmsKeyReference()).isEqualTo("local");
        assertThat(saved.getClientSecretEncrypted()).doesNotContain("sup3r-secret-value");

        when(repository.findByProjectIdAndConnectorId(PROJECT_A, CONNECTOR_ID)).thenReturn(Optional.of(saved));
        assertThat(service.resolve(PROJECT_A, connector).clientSecret()).isEqualTo("sup3r-secret-value");
    }

    @Test
    void theStatusPathNeverDecryptsTheStoredSecret() {
        CredentialService refusesToDecrypt = mock(CredentialService.class);
        when(refusesToDecrypt.decryptSecret(any(), any()))
                .thenThrow(new AssertionError("the status path must never decrypt the client secret"));
        ConnectorAppCredential row = storedRow(PROJECT_A, "project-a-client-id", "sup3r-secret-value");
        when(repository.findByProjectId(PROJECT_A)).thenReturn(List.of(row));
        ConnectorAppCredentialService neverDecrypting = kmsBackedService(refusesToDecrypt);

        assertThat(neverDecrypting.status(PROJECT_A, connector).clientSecretLast4()).isEqualTo("alue");
        assertThat(neverDecrypting.statuses(PROJECT_A, List.of(connector)).get(0).clientSecretLast4())
                .isEqualTo("alue");
    }

    // -------------------------------------------------------------------------------------------
    // [auto] A row written under the pre-envelope scheme fails loudly rather than resolving garbage
    // -------------------------------------------------------------------------------------------

    @Test
    void aPreEnvelopeRowIsRefusedOnResolveRatherThanSilentlyMisread() {
        ConnectorAppCredential legacy = new ConnectorAppCredential();
        legacy.setId("cred-legacy");
        legacy.setProjectId(PROJECT_A);
        legacy.setConnectorId(CONNECTOR_ID);
        legacy.setClientId("project-a-client-id");
        // V132 ciphertext: written under the deployment-wide workflow-secrets key, no kms_key_reference.
        legacy.setClientSecretEncrypted("Y2lwaGVydGV4dC13cml0dGVuLXVuZGVyLXRoZS1vbGQtc2NoZW1l");
        when(repository.findByProjectIdAndConnectorId(PROJECT_A, CONNECTOR_ID))
                .thenReturn(Optional.of(legacy));

        assertThatThrownBy(() -> service.resolve(PROJECT_A, connector))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("re-enter the client secret");
    }

    @Test
    void aPreEnvelopeRowStillShowsInStatusWithNoSecretPreview() {
        ConnectorAppCredential legacy = new ConnectorAppCredential();
        legacy.setProjectId(PROJECT_A);
        legacy.setConnectorId(CONNECTOR_ID);
        legacy.setClientId("project-a-client-id");
        legacy.setClientSecretEncrypted("Y2lwaGVydGV4dC13cml0dGVuLXVuZGVyLXRoZS1vbGQtc2NoZW1l");
        when(repository.findByProjectIdAndConnectorId(PROJECT_A, CONNECTOR_ID))
                .thenReturn(Optional.of(legacy));

        // The catalog and settings pages keep rendering -- the admin has to be able to reach the
        // form that fixes the row -- but nothing claims to know the secret.
        var status = service.status(PROJECT_A, connector);
        assertThat(status.source()).isEqualTo(CredentialSource.PROJECT);
        assertThat(status.clientId()).isEqualTo("project-a-client-id");
        assertThat(status.clientSecretLast4()).isNull();
    }

    @Test
    void rewritingAPreEnvelopeRowMovesItOntoTheEnvelope() {
        ConnectorAppCredential legacy = new ConnectorAppCredential();
        legacy.setProjectId(PROJECT_A);
        legacy.setConnectorId(CONNECTOR_ID);
        legacy.setClientId("project-a-client-id");
        legacy.setClientSecretEncrypted("Y2lwaGVydGV4dC13cml0dGVuLXVuZGVyLXRoZS1vbGQtc2NoZW1l");
        when(repository.findByProjectIdAndConnectorId(PROJECT_A, CONNECTOR_ID))
                .thenReturn(Optional.of(legacy));
        ConnectorAppCredentialService kmsBacked = kmsBackedService(kmsEnvelope());

        kmsBacked.put(PROJECT_A, CONNECTOR_ID, "project-a-client-id", "re-entered-secret", admin());

        ConnectorAppCredential saved = savedRow();
        assertThat(saved.getKmsKeyReference()).isNotBlank();
        assertThat(kmsBacked.resolve(PROJECT_A, connector).clientSecret()).isEqualTo("re-entered-secret");
    }

    // -------------------------------------------------------------------------------------------
    // [auto] Only a project ADMIN may set or clear a project's credentials
    // -------------------------------------------------------------------------------------------

    @Test
    void aNonAdminMemberCannotSetCredentials() {
        assertThatThrownBy(() -> service.put(PROJECT_A, CONNECTOR_ID, "id", "secret", nonAdmin()))
                .isInstanceOf(ForbiddenException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void aNonAdminMemberCannotClearCredentials() {
        storedRow(PROJECT_A, "project-a-client-id", "project-a-client-secret");

        assertThatThrownBy(() -> service.clear(PROJECT_A, CONNECTOR_ID, nonAdmin()))
                .isInstanceOf(ForbiddenException.class);

        verify(repository, never()).delete(any());
    }

    @Test
    void clearingAProjectRowRestoresTheEnvVarFallback() {
        stubDeploymentEnv("deployment-client-id", "deployment-client-secret");
        ConnectorAppCredential row = storedRow(PROJECT_A, "project-a-client-id", "project-a-client-secret");
        assertThat(service.resolve(PROJECT_A, connector).source()).isEqualTo(CredentialSource.PROJECT);

        service.clear(PROJECT_A, CONNECTOR_ID, admin());
        verify(repository).delete(row);

        when(repository.findByProjectIdAndConnectorId(PROJECT_A, CONNECTOR_ID)).thenReturn(Optional.empty());
        ResolvedAppCredentials afterClear = service.resolve(PROJECT_A, connector);
        assertThat(afterClear.source()).isEqualTo(CredentialSource.DEPLOYMENT);
        assertThat(afterClear.clientId()).isEqualTo("deployment-client-id");
    }

    // -------------------------------------------------------------------------------------------
    // OAuth flow threading — the projectId has to reach all three credential reads
    // -------------------------------------------------------------------------------------------

    private OAuthFlowService oauthFlowService() {
        OAuthFlowService flow = new OAuthFlowService(oAuthStateRepository, connectionService,
                connectorRegistry, service, new ObjectMapper(), connectionHealthService);
        ReflectionTestUtils.setField(flow, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(flow, "frontendUrl", "http://localhost:3000");
        when(connectorRegistry.findOAuth2(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        return flow;
    }

    @Test
    void withNoProjectRowTheAuthorizationUrlIsTheExactStringTheEnvVarsAlwaysProduced() {
        stubDeploymentEnv("deployment-client-id", "deployment-client-secret");
        OAuthFlowService flow = oauthFlowService();

        String url = flow.buildAuthorizationUrl(PROJECT_A, CONNECTOR_ID, REDIRECT_URI);

        ArgumentCaptor<IntegrationOAuthState> captor = ArgumentCaptor.forClass(IntegrationOAuthState.class);
        verify(oAuthStateRepository).save(captor.capture());
        String state = captor.getValue().getState();

        assertThat(url).isEqualTo("https://acme.example.com/oauth/authorize"
                + "?client_id=deployment-client-id"
                + "&redirect_uri=" + REDIRECT_URI
                + "&response_type=code"
                + "&scope=acme.read"
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + state);
    }

    @Test
    void theAuthorizationUrlUsesTheProjectsOwnClientIdWhenItHasOne() {
        stubDeploymentEnv("deployment-client-id", "deployment-client-secret");
        storedRow(PROJECT_A, "project-a-client-id", "project-a-client-secret");
        OAuthFlowService flow = oauthFlowService();

        assertThat(flow.buildAuthorizationUrl(PROJECT_A, CONNECTOR_ID, REDIRECT_URI))
                .contains("client_id=project-a-client-id");
        assertThat(flow.buildAuthorizationUrl(PROJECT_B, CONNECTOR_ID, REDIRECT_URI))
                .contains("client_id=deployment-client-id");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void theCallbackExchangesWithTheSameProjectsCredentialsThatStartedTheFlow() {
        stubDeploymentEnv("deployment-client-id", "deployment-client-secret");
        storedRow(PROJECT_A, "project-a-client-id", "project-a-client-secret");
        OAuthFlowService flow = oauthFlowService();

        IntegrationOAuthState oauthState = new IntegrationOAuthState();
        oauthState.setState("state-a");
        oauthState.setProjectId(PROJECT_A);
        oauthState.setConnectorId(CONNECTOR_ID);
        oauthState.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        when(oAuthStateRepository.findById("state-a")).thenReturn(Optional.of(oauthState));

        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setProjectId(PROJECT_A);
        conn.setConnectorId(CONNECTOR_ID);
        when(connectionService.getOrCreateSingle(PROJECT_A, CONNECTOR_ID, AuthType.OAUTH2)).thenReturn(conn);

        ArgumentCaptor<HttpEntity> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq("https://acme.example.com/oauth/token"), eq(HttpMethod.POST),
                requestCaptor.capture(), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(
                        Map.of("access_token", "access-123", "refresh_token", "refresh-456")));

        flow.handleCallback("auth-code", "state-a", REDIRECT_URI);

        assertThat(formValue(requestCaptor.getValue(), "client_id")).isEqualTo("project-a-client-id");
        assertThat(formValue(requestCaptor.getValue(), "client_secret")).isEqualTo("project-a-client-secret");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aRefreshResolvesCredentialsFromTheConnectionsOwnProject() {
        stubDeploymentEnv("deployment-client-id", "deployment-client-secret");
        storedRow(PROJECT_A, "project-a-client-id", "project-a-client-secret");
        OAuthFlowService flow = oauthFlowService();

        Connection connA = new Connection();
        connA.setId("conn-a");
        connA.setProjectId(PROJECT_A);
        connA.setConnectorId(CONNECTOR_ID);
        Connection connB = new Connection();
        connB.setId("conn-b");
        connB.setProjectId(PROJECT_B);
        connB.setConnectorId(CONNECTOR_ID);

        ArgumentCaptor<HttpEntity> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq("https://acme.example.com/oauth/token"), eq(HttpMethod.POST),
                requestCaptor.capture(), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(Map.of("access_token", "new-access")));

        flow.refreshAccessToken(connA, "refresh-a");
        assertThat(formValue(requestCaptor.getValue(), "client_id")).isEqualTo("project-a-client-id");

        flow.refreshAccessToken(connB, "refresh-b");
        assertThat(formValue(requestCaptor.getValue(), "client_id")).isEqualTo("deployment-client-id");
    }

    @Test
    void anUnconfiguredConnectorStillFailsWithTheMessageNamingTheMissingProperty() {
        stubDeploymentEnv("", "");
        OAuthFlowService flow = oauthFlowService();

        assertThatThrownBy(() -> flow.buildAuthorizationUrl(PROJECT_A, CONNECTOR_ID, REDIRECT_URI))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth client credentials not configured: ACME_OAUTH_CLIENT_ID");

        verify(oAuthStateRepository, never()).save(any());
    }

    // --- A connector whose app belongs to the workspace never consults the deployment environment ---

    @Test
    void workspaceOnlyConnectorWithNoRow_resolvesNone_evenWhenTheDeploymentEnvIsSet() {
        when(environment.getProperty("PLATFORM_APP_ID", "")).thenReturn("deployment-app-id");
        when(environment.getProperty("PLATFORM_APP_SECRET", "")).thenReturn("deployment-app-secret");
        when(repository.findByProjectIdAndConnectorId(PROJECT_A, WORKSPACE_ONLY_CONNECTOR_ID))
                .thenReturn(Optional.empty());

        var resolved = service.resolve(PROJECT_A, workspaceOnlyConnector);

        assertThat(resolved.source()).isEqualTo(ConnectorAppCredentialService.CredentialSource.NONE);
        assertThat(resolved.clientId()).isNull();
        assertThat(resolved.clientSecret()).isNull();
        // No env var would fix this, so naming one would send an admin to change something nothing reads.
        assertThat(resolved.missingProperties()).isEmpty();
    }

    @Test
    void workspaceOnlyConnectorStatus_reportsNoneAndFlagsThatNoDeploymentFallbackExists() {
        when(environment.getProperty("PLATFORM_APP_ID", "")).thenReturn("deployment-app-id");
        when(environment.getProperty("PLATFORM_APP_SECRET", "")).thenReturn("deployment-app-secret");
        when(repository.findByProjectIdAndConnectorId(PROJECT_A, WORKSPACE_ONLY_CONNECTOR_ID))
                .thenReturn(Optional.empty());

        var status = service.status(PROJECT_A, workspaceOnlyConnector);

        assertThat(status.source()).isEqualTo(ConnectorAppCredentialService.CredentialSource.NONE);
        assertThat(status.configured()).isFalse();
        assertThat(status.missingProperties()).isEmpty();
        assertThat(status.allowsDeploymentCredentials()).isFalse();
    }

    @Test
    void workspaceOnlyConnectorWithARow_resolvesThatRow() {
        ConnectorAppCredential row = envelopeRow(PROJECT_A, "workspace-app-id", "workspace-app-secret");
        row.setConnectorId(WORKSPACE_ONLY_CONNECTOR_ID);
        when(repository.findByProjectIdAndConnectorId(PROJECT_A, WORKSPACE_ONLY_CONNECTOR_ID))
                .thenReturn(Optional.of(row));

        var resolved = service.resolve(PROJECT_A, workspaceOnlyConnector);

        assertThat(resolved.source()).isEqualTo(ConnectorAppCredentialService.CredentialSource.PROJECT);
        assertThat(resolved.clientId()).isEqualTo("workspace-app-id");
        assertThat(resolved.clientSecret()).isEqualTo("workspace-app-secret");
    }

    @Test
    void statusesMixesBothKindsOfConnectorInOneRead() {
        stubDeploymentEnv("acme-id", "acme-secret");
        when(repository.findByProjectId(PROJECT_A)).thenReturn(List.of());

        var statuses = service.statuses(PROJECT_A, List.of(connector, workspaceOnlyConnector));

        assertThat(statuses).hasSize(2);
        assertThat(statuses.get(0).source()).isEqualTo(ConnectorAppCredentialService.CredentialSource.DEPLOYMENT);
        assertThat(statuses.get(0).allowsDeploymentCredentials()).isTrue();
        assertThat(statuses.get(1).source()).isEqualTo(ConnectorAppCredentialService.CredentialSource.NONE);
        assertThat(statuses.get(1).allowsDeploymentCredentials()).isFalse();
        assertThat(statuses.get(1).missingProperties()).isEmpty();
    }

    @Test
    void workspaceOnlyConnectorWithNoRow_failsTheFlowWithAdviceToEnterCredentials_notAnEnvVarName() {
        when(repository.findByProjectIdAndConnectorId(PROJECT_A, WORKSPACE_ONLY_CONNECTOR_ID))
                .thenReturn(Optional.empty());
        when(connectorRegistry.findOAuth2(WORKSPACE_ONLY_CONNECTOR_ID))
                .thenReturn(Optional.of(workspaceOnlyConnector));
        OAuthFlowService flow = oauthFlowService();

        assertThatThrownBy(() ->
                flow.buildAuthorizationUrl(PROJECT_A, WORKSPACE_ONLY_CONNECTOR_ID, REDIRECT_URI))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Settings -> Integrations")
                .hasMessageNotContaining("PLATFORM_APP_ID");

        verify(oAuthStateRepository, never()).save(any());
    }

    @SuppressWarnings("unchecked")
    private static String formValue(HttpEntity<?> entity, String key) {
        MultiValueMap<String, String> body = (MultiValueMap<String, String>) entity.getBody();
        assertThat(body).isNotNull();
        return body.getFirst(key);
    }
}
