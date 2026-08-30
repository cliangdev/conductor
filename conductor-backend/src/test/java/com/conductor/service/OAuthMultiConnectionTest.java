package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.IntegrationOAuthState;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.FieldType;
import com.conductor.integration.OAuth2Connector;
import com.conductor.integration.connector.gsc.GscConnector;
import com.conductor.integration.connector.tiktok.TikTokConnector;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/**
 * Covers the completion seam {@link OAuthFlowService} grew so the social connectors can finish a
 * consent round trip: a new connection row per authorization for connectors that permit several, the
 * post-exchange completion hook, the account picker, and the provider-specific parameter naming
 * TikTok needs — each pinned against the invariant that Google-backed single-instance connectors
 * behave exactly as they did before any of it existed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthMultiConnectionTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String REDIRECT_URI = "http://localhost:8080/api/v1/oauth/callback";

    @Mock private IntegrationOAuthStateRepository oAuthStateRepository;
    @Mock private ConnectionService connectionService;
    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private Environment environment;
    @Mock private ConnectionHealthService connectionHealthService;
    @Mock private RestTemplate restTemplate;

    private OAuthFlowService service;

    @BeforeEach
    void setUp() {
        service = new OAuthFlowService(oAuthStateRepository, connectionService, connectorRegistry,
                environment, new ObjectMapper(), connectionHealthService);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:3000");
    }

    // ---------------------------------------------------------------------------------------------
    // [auto] A connector permitting multiple connections gets a new connection row per authorization
    // ---------------------------------------------------------------------------------------------

    @Test
    void multiConnectionConnectorGetsItsOwnConnectionRowPerAuthorization() {
        SocialConnector connector = new SocialConnector();
        registerConnector(connector, "social-client-id", "social-client-secret");
        stubTokenExchange("user-token-a", "refresh-a");
        List<Connection> created = stubCreate(connector.getId());

        service.handleCallback("code-a", stubState(connector.getId(), "state-a"), REDIRECT_URI);
        stubTokenExchange("user-token-b", "refresh-b");
        service.handleCallback("code-b", stubState(connector.getId(), "state-b"), REDIRECT_URI);

        verify(connectionService, never()).getOrCreateSingle(anyString(), anyString(), any());
        verify(connectionService, times(2))
                .create(eq(PROJECT_ID), eq(connector.getId()), eq(AuthType.OAUTH2), anyString(), any());
        assertThat(created).hasSize(2);
        assertThat(created).extracting(Connection::getId).doesNotHaveDuplicates();
        assertThat(created).allSatisfy(conn -> assertThat(conn.getStatus()).isEqualTo("ACTIVE"));
    }

    @Test
    void twoAuthorizationsOfTheSameConnectorStoreTheirOwnTokens() {
        SocialConnector connector = new SocialConnector();
        registerConnector(connector, "social-client-id", "social-client-secret");
        List<Connection> created = stubCreate(connector.getId());

        stubTokenExchange("user-token-a", "refresh-a");
        service.handleCallback("code-a", stubState(connector.getId(), "state-a"), REDIRECT_URI);
        stubTokenExchange("user-token-b", "refresh-b");
        service.handleCallback("code-b", stubState(connector.getId(), "state-b"), REDIRECT_URI);

        verify(connectionService).storeTokens(eq(created.get(0)), eq("page-token-for-user-token-a"),
                eq("refresh-a"), any());
        verify(connectionService).storeTokens(eq(created.get(1)), eq("page-token-for-user-token-b"),
                eq("refresh-b"), any());
    }

    // ---------------------------------------------------------------------------------------------
    // [auto] Single-instance connectors are behaviorally unchanged
    // ---------------------------------------------------------------------------------------------

    @Test
    void singleInstanceConnectorStillReusesItsOneConnectionRow() {
        GscConnector gsc = new GscConnector();
        registerConnector(gsc, "google-client-id", "google-client-secret");
        stubTokenExchange("google-access", "google-refresh");
        Connection existing = connection("conn-gsc", "gsc");
        when(connectionService.getOrCreateSingle(PROJECT_ID, "gsc", AuthType.OAUTH2)).thenReturn(existing);

        service.handleCallback("code-1", stubState("gsc", "state-1"), REDIRECT_URI);
        service.handleCallback("code-2", stubState("gsc", "state-2"), REDIRECT_URI);

        verify(connectionService, times(2)).getOrCreateSingle(PROJECT_ID, "gsc", AuthType.OAUTH2);
        verify(connectionService, never()).create(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void connectorWithoutACompletionHookKeepsTheExchangedTokensAndWritesNoConfig() {
        GscConnector gsc = new GscConnector();
        registerConnector(gsc, "google-client-id", "google-client-secret");
        stubTokenExchange("google-access", "google-refresh");
        Connection existing = connection("conn-gsc", "gsc");
        when(connectionService.getOrCreateSingle(PROJECT_ID, "gsc", AuthType.OAUTH2)).thenReturn(existing);

        String redirect = service.handleCallback("code-1", stubState("gsc", "state-1"), REDIRECT_URI);

        verify(connectionService).storeTokens(eq(existing), eq("google-access"), eq("google-refresh"), any());
        verify(connectionService, never()).updateConfig(any(), any());
        verify(connectionService, never()).updateLabel(any(), anyString());
        assertThat(redirect).isEqualTo("http://localhost:3000/app/projects/proj-1/integrations/gsc");
    }

    // ---------------------------------------------------------------------------------------------
    // [auto] The post-exchange completion hook runs and contributes non-secret config,
    //        with secrets kept in the encrypted slot
    // ---------------------------------------------------------------------------------------------

    @Test
    void completionHookRunsAfterTheCodeExchangeAndItsConfigLandsOnTheConnection() {
        SocialConnector connector = new SocialConnector();
        registerConnector(connector, "social-client-id", "social-client-secret");
        stubTokenExchange("user-token-a", "refresh-a");
        List<Connection> created = stubCreate(connector.getId());

        service.handleCallback("code-a", stubState(connector.getId(), "state-a"), REDIRECT_URI);

        assertThat(connector.completionRequests).hasSize(1);
        assertThat(connector.completionRequests.get(0).accessToken()).isEqualTo("user-token-a");

        ArgumentCaptor<Map<String, Object>> configCaptor = configCaptor();
        verify(connectionService).updateConfig(eq(created.get(0)), configCaptor.capture());
        assertThat(configCaptor.getValue())
                .containsEntry("pageId", "page-1")
                .containsEntry("pageName", "Acme Page");
    }

    @Test
    void theCompletionHooksCredentialGoesToTheEncryptedSlotNotThePlaintextConfig() {
        SocialConnector connector = new SocialConnector();
        registerConnector(connector, "social-client-id", "social-client-secret");
        stubTokenExchange("user-token-a", "refresh-a");
        List<Connection> created = stubCreate(connector.getId());

        service.handleCallback("code-a", stubState(connector.getId(), "state-a"), REDIRECT_URI);

        // The per-account credential the hook minted is what gets encrypted...
        verify(connectionService).storeTokens(eq(created.get(0)), eq("page-token-for-user-token-a"),
                eq("refresh-a"), any());

        // ...and neither it nor the user token the exchange returned may appear in plaintext config.
        ArgumentCaptor<Map<String, Object>> configCaptor = configCaptor();
        verify(connectionService).updateConfig(eq(created.get(0)), configCaptor.capture());
        assertThat(configCaptor.getValue().values())
                .doesNotContain("page-token-for-user-token-a", "user-token-a", "refresh-a");
        assertThat(configCaptor.getValue().keySet())
                .noneSatisfy(key -> assertThat(key.toLowerCase()).contains("token"));
    }

    @Test
    void theCompletionHookNamesTheConnectionSoTwoAccountsStayDistinct() {
        SocialConnector connector = new SocialConnector();
        registerConnector(connector, "social-client-id", "social-client-secret");
        stubTokenExchange("user-token-a", "refresh-a");
        List<Connection> created = stubCreate(connector.getId());

        service.handleCallback("code-a", stubState(connector.getId(), "state-a"), REDIRECT_URI);

        verify(connectionService).updateLabel(created.get(0), "Acme Page");
    }

    // ---------------------------------------------------------------------------------------------
    // [auto] An account picker lets the admin choose which Page/account the connection maps to
    // ---------------------------------------------------------------------------------------------

    @Test
    void aConnectorNeedingAnAccountChoiceParksTheConnectionAndSendsTheAdminToThePicker() {
        PickerConnector connector = new PickerConnector();
        registerConnector(connector, "picker-client-id", "picker-client-secret");
        stubTokenExchange("user-token-a", "refresh-a");
        List<Connection> created = stubCreate(connector.getId());

        String redirect = service.handleCallback("code-a", stubState(connector.getId(), "state-a"), REDIRECT_URI);

        // The grant is persisted so the picker can enumerate against it, but nothing is finalized yet.
        verify(connectionService).storeTokens(eq(created.get(0)), eq("user-token-a"), eq("refresh-a"), any());
        verify(connectionService, never()).updateConfig(any(), any());
        assertThat(connector.completionRequests).isEmpty();
        assertThat(redirect).isEqualTo("http://localhost:3000/app/projects/proj-1/integrations/picker"
                + "?selectAccount=" + created.get(0).getId());
    }

    @Test
    void thePickerListsTheAccountsTheGrantCoversWithoutExposingTheirCredentials() {
        PickerConnector connector = new PickerConnector();
        registerConnector(connector, "picker-client-id", "picker-client-secret");
        Connection conn = connection("conn-picker", connector.getId());
        when(connectionService.decrypt(conn))
                .thenReturn(new DecryptedCredentials("user-token-a", "refresh-a", null, Map.of()));

        List<OAuth2Connector.OAuthAccount> accounts = service.listAuthorizableAccounts(conn);

        assertThat(accounts).extracting(OAuth2Connector.OAuthAccount::id)
                .containsExactly("page-1", "page-2");
        assertThat(accounts).extracting(OAuth2Connector.OAuthAccount::label)
                .containsExactly("Acme Page", "Beta Page");
    }

    @Test
    void selectingAnAccountFinalizesTheConnectionWithThatAccountsConfigAndCredential() {
        PickerConnector connector = new PickerConnector();
        registerConnector(connector, "picker-client-id", "picker-client-secret");
        Connection conn = connection("conn-picker", connector.getId());
        when(connectionService.decrypt(conn))
                .thenReturn(new DecryptedCredentials("user-token-a", "refresh-a", null, Map.of()));

        service.completeAccountSelection(conn, "page-2");

        assertThat(connector.completionRequests).hasSize(1);
        assertThat(connector.completionRequests.get(0).selectedAccountId()).isEqualTo("page-2");

        verify(connectionService).storeTokens(eq(conn), eq("page-token-for-page-2"), eq("refresh-a"), any());
        ArgumentCaptor<Map<String, Object>> configCaptor = configCaptor();
        verify(connectionService).updateConfig(eq(conn), configCaptor.capture());
        assertThat(configCaptor.getValue())
                .containsEntry("pageId", "page-2")
                .containsEntry("pageName", "Beta Page");
        assertThat(configCaptor.getValue().values()).doesNotContain("page-token-for-page-2", "user-token-a");
    }

    // ---------------------------------------------------------------------------------------------
    // [auto] TikTok's consent URL and token bodies use client_key with comma-separated scopes,
    //        while Google connectors are unchanged
    // ---------------------------------------------------------------------------------------------

    @Test
    void tikTokConsentUrlCarriesClientKeyAndACommaSeparatedScopeList() {
        TikTokConnector tiktok = new StubCompletionTikTokConnector();
        registerConnector(tiktok, "tiktok-key-123", "tiktok-secret-456");

        String url = service.buildAuthorizationUrl(PROJECT_ID, "tiktok", REDIRECT_URI);

        assertThat(url).startsWith("https://www.tiktok.com/v2/auth/authorize/?");
        assertThat(url).contains("client_key=tiktok-key-123");
        assertThat(url).doesNotContain("client_id=");
        assertThat(url).contains("scope=user.info.basic,video.publish,video.upload");
        assertThat(url).doesNotContain("access_type").doesNotContain("prompt=");
    }

    @Test
    void tikTokTokenExchangeBodyCarriesClientKey() {
        TikTokConnector tiktok = new StubCompletionTikTokConnector();
        registerConnector(tiktok, "tiktok-key-123", "tiktok-secret-456");
        stubTokenExchange("tiktok-access", "tiktok-refresh");
        List<Connection> created = stubCreate("tiktok");

        service.handleCallback("code-a", stubState("tiktok", "state-a"), REDIRECT_URI);

        assertThat(formBody(capturedRequest())).containsEntry("client_key", "tiktok-key-123");
        assertThat(formBody(capturedRequest())).doesNotContainKey("client_id");
        assertThat(formBody(capturedRequest())).containsEntry("client_secret", "tiktok-secret-456");
        assertThat(created).hasSize(1);
    }

    @Test
    void tikTokRefreshBodyCarriesClientKey() {
        TikTokConnector tiktok = new StubCompletionTikTokConnector();
        registerConnector(tiktok, "tiktok-key-123", "tiktok-secret-456");
        Connection conn = connection("conn-tiktok", "tiktok");
        stubTokenExchange("refreshed-access", null);

        service.refreshAccessToken(conn, "tiktok-refresh");

        assertThat(formBody(capturedRequest())).containsEntry("client_key", "tiktok-key-123");
        assertThat(formBody(capturedRequest())).doesNotContainKey("client_id");
    }

    @Test
    void googleConsentUrlIsByteForByteUnchanged() {
        GscConnector gsc = new GscConnector();
        registerConnector(gsc, "google-client-id", "google-client-secret");

        String url = service.buildAuthorizationUrl(PROJECT_ID, "gsc", REDIRECT_URI);

        ArgumentCaptor<IntegrationOAuthState> captor = ArgumentCaptor.forClass(IntegrationOAuthState.class);
        verify(oAuthStateRepository).save(captor.capture());

        assertThat(url).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=google-client-id"
                + "&redirect_uri=" + REDIRECT_URI
                + "&response_type=code"
                + "&scope=https://www.googleapis.com/auth/webmasters.readonly"
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + captor.getValue().getState());
    }

    @Test
    void googleTokenExchangeBodyIsUnchanged() {
        GscConnector gsc = new GscConnector();
        registerConnector(gsc, "google-client-id", "google-client-secret");
        stubTokenExchange("google-access", "google-refresh");
        when(connectionService.getOrCreateSingle(PROJECT_ID, "gsc", AuthType.OAUTH2))
                .thenReturn(connection("conn-gsc", "gsc"));

        service.handleCallback("code-1", stubState("gsc", "state-1"), REDIRECT_URI);

        assertThat(formBody(capturedRequest()))
                .containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "code-1")
                .containsEntry("redirect_uri", REDIRECT_URI)
                .containsEntry("client_id", "google-client-id")
                .containsEntry("client_secret", "google-client-secret")
                .doesNotContainKey("client_key");
    }

    @Test
    void aMultiScopeGoogleConnectorStillJoinsScopesWithASpace() {
        MultiScopeGoogleConnector google = new MultiScopeGoogleConnector();
        registerConnector(google, "google-client-id", "google-client-secret");

        String url = service.buildAuthorizationUrl(PROJECT_ID, google.getId(), REDIRECT_URI);

        assertThat(url).contains("scope=https://www.googleapis.com/auth/a"
                + " https://www.googleapis.com/auth/b");
    }

    // ---- fixtures -------------------------------------------------------------------------------

    /** Multi-connection connector with a completion hook, standing in for Meta/YouTube/TikTok. */
    private static class SocialConnector implements OAuth2Connector {
        final List<OAuthCompletionRequest> completionRequests = new ArrayList<>();

        @Override public String getId() { return "social"; }
        @Override public List<String> oauthScopes() { return List.of("pages_manage_posts"); }
        @Override public String authorizationUrl() { return "https://social.example.com/dialog/oauth"; }
        @Override public String tokenUrl() { return "https://social.example.com/oauth/access_token"; }
        @Override public String clientIdProperty() { return "SOCIAL_APP_ID"; }
        @Override public String clientSecretProperty() { return "SOCIAL_APP_SECRET"; }
        @Override public Map<String, String> extraAuthorizationParams() { return Map.of(); }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("social", "Social", ConnectorCategory.MARKETING, "Social", "SO");
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(false, List.of());
        }

        @Override
        public OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
            completionRequests.add(request);
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("pageId", "page-1");
            config.put("pageName", "Acme Page");
            return new OAuthCompletion("page-token-for-" + request.accessToken(), null, "Acme Page", config);
        }
    }

    /** Multi-connection connector whose grant covers several accounts, so a human must pick one. */
    private static class PickerConnector implements OAuth2Connector {
        final List<OAuthCompletionRequest> completionRequests = new ArrayList<>();
        private final Map<String, String> pages =
                new LinkedHashMap<>(Map.of("page-1", "Acme Page", "page-2", "Beta Page"));

        @Override public String getId() { return "picker"; }
        @Override public List<String> oauthScopes() { return List.of("pages_show_list"); }
        @Override public String authorizationUrl() { return "https://picker.example.com/dialog/oauth"; }
        @Override public String tokenUrl() { return "https://picker.example.com/oauth/access_token"; }
        @Override public String clientIdProperty() { return "PICKER_APP_ID"; }
        @Override public String clientSecretProperty() { return "PICKER_APP_SECRET"; }
        @Override public Map<String, String> extraAuthorizationParams() { return Map.of(); }
        @Override public boolean requiresAccountSelection() { return true; }

        @Override
        public List<OAuthAccount> listAuthorizableAccounts(String accessToken) {
            return List.of(new OAuthAccount("page-1", "Acme Page"), new OAuthAccount("page-2", "Beta Page"));
        }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("picker", "Picker", ConnectorCategory.MARKETING, "Picker", "PK");
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(false, List.of(
                    ConnectorConfigField.userInput("pageId", "Page", "Page to publish to",
                            FieldType.SELECT, true)));
        }

        @Override
        public OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
            completionRequests.add(request);
            String pageId = request.selectedAccountId();
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("pageId", pageId);
            config.put("pageName", pages.get(pageId));
            return new OAuthCompletion("page-token-for-" + pageId, null, pages.get(pageId), config);
        }
    }

    /** Two Google scopes, to lock the space delimiter that RFC 6749 (and Google) require. */
    private static class MultiScopeGoogleConnector implements OAuth2Connector {
        @Override public String getId() { return "multi-scope-google"; }

        @Override
        public List<String> oauthScopes() {
            return List.of("https://www.googleapis.com/auth/a", "https://www.googleapis.com/auth/b");
        }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("multi-scope-google", "Multi", ConnectorCategory.ANALYTICS, "Multi", "MG");
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(true, List.of());
        }
    }

    /**
     * The real {@link TikTokConnector} — its endpoints, {@code client_key} naming and comma scope
     * delimiter are exactly what these tests assert on — with only the creator_info round trip
     * stubbed out, since the package-private client seam isn't reachable from this package.
     */
    private static class StubCompletionTikTokConnector extends TikTokConnector {
        @Override
        public OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
            return OAuthCompletion.unchanged();
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void registerConnector(OAuth2Connector connector, String clientId, String clientSecret) {
        when(connectorRegistry.findOAuth2(connector.getId())).thenReturn(Optional.of(connector));
        when(environment.getProperty(connector.clientIdProperty(), "")).thenReturn(clientId);
        when(environment.getProperty(connector.clientSecretProperty(), "")).thenReturn(clientSecret);
    }

    private String stubState(String connectorId, String state) {
        IntegrationOAuthState oauthState = new IntegrationOAuthState();
        oauthState.setState(state);
        oauthState.setProjectId(PROJECT_ID);
        oauthState.setConnectorId(connectorId);
        oauthState.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        when(oAuthStateRepository.findById(state)).thenReturn(Optional.of(oauthState));
        return state;
    }

    private static Connection connection(String id, String connectorId) {
        Connection conn = new Connection();
        conn.setId(id);
        conn.setProjectId(PROJECT_ID);
        conn.setConnectorId(connectorId);
        conn.setStatus("ACTIVE");
        return conn;
    }

    /** Makes {@code create} mint a distinct row per call, the way the real service would. */
    private List<Connection> stubCreate(String connectorId) {
        List<Connection> created = new ArrayList<>();
        when(connectionService.create(eq(PROJECT_ID), eq(connectorId), eq(AuthType.OAUTH2), anyString(), any()))
                .thenAnswer(invocation -> {
                    Connection conn = connection("conn-" + connectorId + "-" + (created.size() + 1), connectorId);
                    created.add(conn);
                    return conn;
                });
        return created;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubTokenExchange(String accessToken, String refreshToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", accessToken);
        if (refreshToken != null) {
            body.put("refresh_token", refreshToken);
        }
        body.put("expires_in", 3600);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(body));
    }

    @SuppressWarnings("rawtypes")
    private HttpEntity<?> capturedRequest() {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, org.mockito.Mockito.atLeastOnce())
                .exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> formBody(HttpEntity<?> entity) {
        MultiValueMap<String, String> body = (MultiValueMap<String, String>) entity.getBody();
        assertThat(body).isNotNull();
        return body.toSingleValueMap();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> configCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
