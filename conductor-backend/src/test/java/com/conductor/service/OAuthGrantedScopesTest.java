package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.IntegrationOAuthState;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.OAuth2Connector;
import com.conductor.repository.ConnectorAppCredentialRepository;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scopes an authorization granted are recorded on the connection that ends up holding the grant
 * (including the older row a re-authorization is merged into), and the connector's current scopes minus
 * that record are what the UI prompts a reconnect for. Pure Mockito, like {@link OAuthReconnectMergeTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthGrantedScopesTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String REDIRECT_URI = "http://localhost:8080/api/v1/oauth/callback";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private static final List<String> REQUESTED = List.of("a", "b", "c");

    @Mock private IntegrationOAuthStateRepository oAuthStateRepository;
    @Mock private ConnectionService connectionService;
    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private Environment environment;
    @Mock private ConnectionHealthService connectionHealthService;
    @Mock private RestTemplate restTemplate;
    @Mock private ConnectorAppCredentialRepository appCredentialRepository;
    @Mock private ProjectSecurityService projectSecurityService;
    @Mock private ConnectionDisconnectService connectionDisconnectService;

    private OAuthFlowService service;
    private final List<Connection> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ConnectorAppCredentialService appCredentialService = new ConnectorAppCredentialService(
                appCredentialRepository, mock(CredentialService.class), environment, projectSecurityService);
        service = new OAuthFlowService(oAuthStateRepository, connectionService, connectorRegistry,
                appCredentialService, new ObjectMapper(), connectionHealthService, connectionDisconnectService);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:3000");
        when(connectionService.list(eq(PROJECT_ID), anyString())).thenAnswer(inv -> List.copyOf(rows));
        when(connectionDisconnectService.mergeDuplicate(anyString(), anyString())).thenReturn(true);
    }

    // ---- recording ---------------------------------------------------------------------------------

    @Test
    void recordsWhatTheConnectorHookReports() {
        TestConnector connector = new TestConnector(Optional.of(List.of("a", "c")), false);
        register(connector);
        stubTokenExchange(null);
        Connection fresh = stubCreate(T0);

        service.handleCallback("code", stubState(), REDIRECT_URI);

        assertThat(connector.hookToken).isEqualTo("user-access");
        verify(connectionService).updateGrantedScopes(fresh, List.of("a", "c"));
    }

    @Test
    void fallsBackToTheTokenResponseScopeSplitOnDelimiterWhitespaceAndCommas() {
        register(new TestConnector(Optional.empty(), false));
        stubTokenExchange("a,b c");
        Connection fresh = stubCreate(T0);

        service.handleCallback("code", stubState(), REDIRECT_URI);

        verify(connectionService).updateGrantedScopes(fresh, List.of("a", "b", "c"));
    }

    @Test
    void fallsBackToTheRequestedScopesWhenNothingElseSaysWhatWasGranted() {
        register(new TestConnector(Optional.empty(), false));
        stubTokenExchange(null);
        Connection fresh = stubCreate(T0);

        service.handleCallback("code", stubState(), REDIRECT_URI);

        verify(connectionService).updateGrantedScopes(fresh, REQUESTED);
    }

    @Test
    void aHookThatThrowsNeverFailsTheAuthorization() {
        TestConnector connector = new TestConnector(Optional.empty(), false);
        connector.hookThrows = true;
        register(connector);
        stubTokenExchange(null);
        Connection fresh = stubCreate(T0);

        service.handleCallback("code", stubState(), REDIRECT_URI);

        verify(connectionService).updateGrantedScopes(fresh, REQUESTED);
    }

    @Test
    void reauthorizingAnExistingAccountWritesTheNewListToTheOlderRow() {
        Connection older = row("conn-older", T0, "{\"pageId\":\"p1\"}");
        rows.add(older);
        register(new TestConnector(Optional.of(List.of("a", "b", "c")), false));
        stubTokenExchange(null);
        Connection fresh = stubCreate(T0.plusDays(30));

        service.handleCallback("code", stubState(), REDIRECT_URI);

        verify(connectionService).updateGrantedScopes(older, List.of("a", "b", "c"));
        verify(connectionService, never()).updateGrantedScopes(eq(fresh), any());
        verify(connectionDisconnectService).mergeDuplicate(fresh.getId(), older.getId());
    }

    // ---- account selection -------------------------------------------------------------------------

    @Test
    void theCallbackParksTheGrantedScopesOnTheRowThatAwaitsSelection() {
        register(new TestConnector(Optional.of(List.of("a", "b", "c")), true));
        stubTokenExchange(null);
        Connection fresh = stubCreate(T0);

        service.handleCallback("code", stubState(), REDIRECT_URI);

        verify(connectionService).updateGrantedScopes(fresh, List.of("a", "b", "c"));
        verify(connectionService).storeTokens(eq(fresh), eq("user-access"), any(), any());
    }

    @Test
    void selectionCarriesTheParkedScopesIntoTheRowItMergesInto() {
        Connection older = row("conn-older", T0, "{\"pageId\":\"p1\"}");
        Connection parked = row("conn-parked", T0.plusDays(30), null);
        rows.addAll(List.of(older, parked));
        register(new TestConnector(Optional.empty(), true));
        when(connectionService.decrypt(parked))
                .thenReturn(new DecryptedCredentials("user-token", "refresh", null, Map.of()));
        when(connectionService.readGrantedScopes(parked)).thenReturn(List.of("a", "b", "c"));

        Connection result = service.completeAccountSelection(parked, "p1");

        assertThat(result).isSameAs(older);
        verify(connectionService).updateGrantedScopes(older, List.of("a", "b", "c"));
    }

    @Test
    void selectionWithNoParkedScopesLeavesTheTargetsRecordAlone() {
        Connection parked = row("conn-parked", T0, null);
        rows.add(parked);
        register(new TestConnector(Optional.empty(), true));
        when(connectionService.decrypt(parked))
                .thenReturn(new DecryptedCredentials("user-token", "refresh", null, Map.of()));
        when(connectionService.readGrantedScopes(parked)).thenReturn(null);

        service.completeAccountSelection(parked, "p1");

        verify(connectionService, never()).updateGrantedScopes(any(), any());
    }

    // ---- missing scopes ----------------------------------------------------------------------------

    @Test
    void unknownGrantReportsNothingMissing() {
        register(new TestConnector(Optional.empty(), false));
        Connection conn = oauthRow();
        when(connectionService.readGrantedScopes(conn)).thenReturn(null);

        assertThat(service.missingScopes(conn)).isEmpty();
    }

    @Test
    void aSupersetOfTheCurrentScopesReportsNothingMissing() {
        register(new TestConnector(Optional.empty(), false));
        Connection conn = oauthRow();
        when(connectionService.readGrantedScopes(conn)).thenReturn(List.of("a", "b", "c", "old"));

        assertThat(service.missingScopes(conn)).isEmpty();
    }

    @Test
    void oneNewScopeIsReportedIgnoringCaseAndOrder() {
        register(new TestConnector(Optional.empty(), false));
        Connection conn = oauthRow();
        when(connectionService.readGrantedScopes(conn)).thenReturn(List.of("C", "A"));

        assertThat(service.missingScopes(conn)).containsExactly("b");
    }

    @Test
    void nonOAuthConnectionsNeverReportMissingScopes() {
        Connection conn = oauthRow();
        conn.setAuthType(AuthType.API_KEY.name());
        when(connectionService.readGrantedScopes(conn)).thenReturn(List.of());

        assertThat(service.missingScopes(conn)).isEmpty();
    }

    // ---- doubles and helpers -----------------------------------------------------------------------

    private static class TestConnector implements OAuth2Connector {
        private final Optional<List<String>> hook;
        private final boolean selection;
        String hookToken;
        boolean hookThrows;

        TestConnector(Optional<List<String>> hook, boolean selection) {
            this.hook = hook;
            this.selection = selection;
        }

        @Override public String getId() { return "scoped"; }
        @Override public List<String> oauthScopes() { return REQUESTED; }
        @Override public String authorizationUrl() { return "https://scoped.example.com/oauth"; }
        @Override public String tokenUrl() { return "https://scoped.example.com/token"; }
        @Override public String clientIdProperty() { return "SCOPED_APP_ID"; }
        @Override public String clientSecretProperty() { return "SCOPED_APP_SECRET"; }
        @Override public boolean requiresAccountSelection() { return selection; }

        @Override
        public Optional<String> accountIdentity(Map<String, Object> config) {
            return config.get("pageId") instanceof String id ? Optional.of(id) : Optional.empty();
        }

        @Override
        public Optional<List<String>> grantedScopes(String userAccessToken) {
            hookToken = userAccessToken;
            if (hookThrows) {
                throw new IllegalStateException("boom");
            }
            return hook;
        }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("scoped", "Scoped", ConnectorCategory.MARKETING, "Scoped", "SC");
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(false, List.of());
        }

        @Override
        public OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("pageId", request.selectedAccountId() != null ? request.selectedAccountId() : "p1");
            return new OAuthCompletion("page-token", null, null, config);
        }
    }

    private void register(OAuth2Connector connector) {
        when(connectorRegistry.findOAuth2(connector.getId())).thenReturn(Optional.of(connector));
        when(environment.getProperty(connector.clientIdProperty(), "")).thenReturn("client-id");
        when(environment.getProperty(connector.clientSecretProperty(), "")).thenReturn("client-secret");
    }

    private String stubState() {
        IntegrationOAuthState oauthState = new IntegrationOAuthState();
        oauthState.setState("state-1");
        oauthState.setProjectId(PROJECT_ID);
        oauthState.setConnectorId("scoped");
        oauthState.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        when(oAuthStateRepository.findById("state-1")).thenReturn(Optional.of(oauthState));
        return "state-1";
    }

    private static Connection row(String id, OffsetDateTime createdAt, String configJson) {
        Connection conn = new Connection();
        conn.setId(id);
        conn.setProjectId(PROJECT_ID);
        conn.setConnectorId("scoped");
        conn.setStatus("ACTIVE");
        conn.setAuthType(AuthType.OAUTH2.name());
        conn.setCreatedAt(createdAt);
        conn.setConfigJson(configJson);
        return conn;
    }

    private Connection oauthRow() {
        return row("conn-x", T0, null);
    }

    private Connection stubCreate(OffsetDateTime createdAt) {
        Connection fresh = row("conn-fresh", createdAt, null);
        rows.add(fresh);
        when(connectionService.create(eq(PROJECT_ID), eq("scoped"), eq(AuthType.OAUTH2), anyString(), any()))
                .thenReturn(fresh);
        return fresh;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubTokenExchange(String scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", "user-access");
        body.put("refresh_token", "user-refresh");
        body.put("expires_in", 3600);
        if (scope != null) {
            body.put("scope", scope);
        }
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(body));
    }
}
