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
import com.conductor.integration.connector.tiktok.TikTokConnector;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Re-authorizing an account a multi-connection connector already has must refresh that connection, not
 * add a second row for the same account (the TikTok sandbox-to-production switch is the real case). Pure
 * Mockito: the merge decision lives in {@link OAuthFlowService}, so no Spring context is needed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthReconnectMergeTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String REDIRECT_URI = "http://localhost:8080/api/v1/oauth/callback";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

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
        // The project's rows as the repository would list them: whatever the test put in `rows`.
        when(connectionService.list(eq(PROJECT_ID), anyString())).thenAnswer(inv -> List.copyOf(rows));
        when(connectionDisconnectService.mergeDuplicate(anyString(), anyString())).thenReturn(true);
    }

    // ---- TikTok: the callback path -----------------------------------------------------------------

    @Test
    void reauthorizingTheSameTikTokUsernameRefreshesTheOriginalRowInsteadOfAddingASecond() {
        Connection original = row("conn-original", "tiktok", T0, "{\"creatorUsername\":\"rexipe\",\"creatorNickname\":\"Rexipe\"}");
        rows.add(original);
        TikTokWithCreator tiktok = new TikTokWithCreator("Rexipe"); // case differs on purpose
        registerConnector(tiktok);
        stubTokenExchange("new-access", "new-refresh");
        Connection fresh = stubCreate("tiktok", T0.plusDays(30));

        String redirect = service.handleCallback("code", stubState("tiktok"), REDIRECT_URI);

        // The original id survives and carries the new grant; the fresh row never got one.
        verify(connectionService).storeTokens(eq(original), eq("new-access"), eq("new-refresh"), any());
        verify(connectionService, never()).storeTokens(eq(fresh), anyString(), any(), any());
        verify(connectionService).updateConfig(eq(original), any());
        verify(connectionDisconnectService).mergeDuplicate(fresh.getId(), original.getId());
        verify(connectionService, never()).delete(original.getId());
        assertThat(redirect).isEqualTo("http://localhost:3000/app/projects/proj-1/integrations/tiktok");
    }

    @Test
    void aDifferentTikTokUsernameStillGetsItsOwnRow() {
        Connection original = row("conn-original", "tiktok", T0, "{\"creatorUsername\":\"rexipe\"}");
        rows.add(original);
        registerConnector(new TikTokWithCreator("someone_else"));
        stubTokenExchange("new-access", "new-refresh");
        Connection fresh = stubCreate("tiktok", T0.plusDays(30));

        service.handleCallback("code", stubState("tiktok"), REDIRECT_URI);

        verify(connectionService).storeTokens(eq(fresh), eq("new-access"), eq("new-refresh"), any());
        verify(connectionService, never()).storeTokens(eq(original), anyString(), any(), any());
        verify(connectionService, never()).delete(anyString());
        verify(connectionDisconnectService, never()).mergeDuplicate(anyString(), anyString());
    }

    @Test
    void existingDuplicatesCollapseIntoTheOldestRowAndTheFreshRowIsDiscarded() {
        Connection oldest = row("conn-a", "tiktok", T0, "{\"creatorUsername\":\"rexipe\"}");
        Connection newer = row("conn-b", "tiktok", T0.plusDays(10), "{\"creatorUsername\":\"rexipe\"}");
        Connection other = row("conn-c", "tiktok", T0.plusDays(11), "{\"creatorUsername\":\"another\"}");
        // Listed newest-first on purpose: the survivor must be chosen by age, not list order.
        rows.addAll(List.of(other, newer, oldest));
        registerConnector(new TikTokWithCreator("rexipe"));
        stubTokenExchange("new-access", "new-refresh");
        Connection fresh = stubCreate("tiktok", T0.plusDays(30));

        service.handleCallback("code", stubState("tiktok"), REDIRECT_URI);

        verify(connectionService).storeTokens(eq(oldest), eq("new-access"), eq("new-refresh"), any());
        verify(connectionDisconnectService).mergeDuplicate("conn-b", "conn-a");
        verify(connectionDisconnectService, never()).mergeDuplicate(eq("conn-c"), anyString());
        verify(connectionDisconnectService).mergeDuplicate(fresh.getId(), "conn-a");
    }

    @Test
    void aDuplicateThatCannotBeMergedNeverFailsTheAuthorization() {
        Connection oldest = row("conn-a", "tiktok", T0, "{\"creatorUsername\":\"rexipe\"}");
        Connection waiting = row("conn-b", "tiktok", T0.plusDays(10), "{\"creatorUsername\":\"rexipe\"}");
        Connection broken = row("conn-c", "tiktok", T0.plusDays(11), "{\"creatorUsername\":\"rexipe\"}");
        rows.addAll(List.of(oldest, waiting, broken));
        // conn-b has a Post still waiting on it (kept: false); conn-c blows up outright.
        when(connectionDisconnectService.mergeDuplicate("conn-b", "conn-a")).thenReturn(false);
        doThrow(new IllegalStateException("boom")).when(connectionDisconnectService)
                .mergeDuplicate("conn-c", "conn-a");
        registerConnector(new TikTokWithCreator("rexipe"));
        stubTokenExchange("new-access", "new-refresh");
        Connection fresh = stubCreate("tiktok", T0.plusDays(30));

        service.handleCallback("code", stubState("tiktok"), REDIRECT_URI);

        verify(connectionService).storeTokens(eq(oldest), eq("new-access"), eq("new-refresh"), any());
        verify(connectionDisconnectService).mergeDuplicate(fresh.getId(), "conn-a");
        verify(connectionService, never()).delete("conn-b");
    }

    // ---- Meta-style: the account-selection path ----------------------------------------------------

    @Test
    void selectingAPageThatAlreadyHasAConnectionMergesIntoItAndReturnsTheSurvivor() {
        Connection existing = row("conn-page-2", "picker", T0, "{\"pageId\":\"page-2\",\"pageName\":\"Beta Page\"}");
        Connection parked = row("conn-parked", "picker", T0.plusDays(30), null);
        rows.addAll(List.of(existing, parked));
        registerConnector(new PageConnector());
        stubParkedGrant(parked);

        Connection result = service.completeAccountSelection(parked, "page-2");

        assertThat(result).isSameAs(existing);
        verify(connectionService).storeTokens(eq(existing), eq("page-token-for-page-2"), eq("refresh-a"), any());
        verify(connectionService).updateConfig(eq(existing), any());
        verify(connectionDisconnectService).mergeDuplicate("conn-parked", "conn-page-2");
        verify(connectionService, never()).storeTokens(eq(parked), anyString(), any(), any());
    }

    @Test
    void selectingADifferentPageKeepsTheNewConnectionAndDeletesNothing() {
        Connection existing = row("conn-page-1", "picker", T0, "{\"pageId\":\"page-1\"}");
        Connection parked = row("conn-parked", "picker", T0.plusDays(30), null);
        rows.addAll(List.of(existing, parked));
        registerConnector(new PageConnector());
        stubParkedGrant(parked);

        Connection result = service.completeAccountSelection(parked, "page-2");

        assertThat(result).isSameAs(parked);
        verify(connectionService).storeTokens(eq(parked), eq("page-token-for-page-2"), eq("refresh-a"), any());
        verify(connectionService, never()).delete(anyString());
    }

    @Test
    void aRowNewerThanTheOneBeingFinishedIsNeverTheSurvivor() {
        // Re-picking on an older row must not discard it in favour of a younger row for the same Page.
        Connection older = row("conn-older", "picker", T0, null);
        Connection younger = row("conn-younger", "picker", T0.plusDays(5), "{\"pageId\":\"page-2\"}");
        rows.addAll(List.of(older, younger));
        registerConnector(new PageConnector());
        stubParkedGrant(older);

        Connection result = service.completeAccountSelection(older, "page-2");

        assertThat(result).isSameAs(older);
        verify(connectionService, never()).delete(anyString());
    }

    // ---- no identity: today's behaviour ------------------------------------------------------------

    @Test
    void aConnectorThatReportsNoIdentityKeepsAddingARowPerAuthorization() {
        // Same pageId on both: only an identity-reporting connector may treat that as one account.
        Connection existing = row("conn-existing", "social", T0, "{\"pageId\":\"page-1\"}");
        rows.add(existing);
        registerConnector(new NoIdentityConnector());
        stubTokenExchange("new-access", "new-refresh");
        Connection fresh = stubCreate("social", T0.plusDays(30));

        service.handleCallback("code", stubState("social"), REDIRECT_URI);

        verify(connectionService).storeTokens(eq(fresh), eq("new-access"), eq("new-refresh"), any());
        verify(connectionService, never()).delete(anyString());
        verify(connectionDisconnectService, never()).mergeDuplicate(anyString(), anyString());
    }

    // ---- doubles and helpers -----------------------------------------------------------------------

    /** The real TikTok connector, only the creator_info round trip replaced by a canned username. */
    private static class TikTokWithCreator extends TikTokConnector {
        private final String username;

        TikTokWithCreator(String username) {
            this.username = username;
        }

        @Override
        public OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("creatorUsername", username);
            config.put("creatorNickname", "Rexipe");
            return new OAuthCompletion(null, null, "Rexipe", config);
        }
    }

    /** Meta-shaped: a grant over several Pages, the chosen Page's id is the account identity. */
    private static class PageConnector implements OAuth2Connector {
        @Override public String getId() { return "picker"; }
        @Override public List<String> oauthScopes() { return List.of("pages_show_list"); }
        @Override public String authorizationUrl() { return "https://picker.example.com/dialog/oauth"; }
        @Override public String tokenUrl() { return "https://picker.example.com/oauth/access_token"; }
        @Override public String clientIdProperty() { return "PICKER_APP_ID"; }
        @Override public String clientSecretProperty() { return "PICKER_APP_SECRET"; }
        @Override public boolean requiresAccountSelection() { return true; }

        @Override
        public Optional<String> accountIdentity(Map<String, Object> config) {
            return config.get("pageId") instanceof String id ? Optional.of(id) : Optional.empty();
        }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("picker", "Picker", ConnectorCategory.MARKETING, "Picker", "PK");
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(false, List.of());
        }

        @Override
        public OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("pageId", request.selectedAccountId());
            return new OAuthCompletion("page-token-for-" + request.selectedAccountId(), null, null, config);
        }
    }

    /** Multi-connection connector with no {@code accountIdentity} override. */
    private static class NoIdentityConnector implements OAuth2Connector {
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
            return new OAuthCompletion(null, null, "Acme Page", Map.of("pageId", "page-1"));
        }
    }

    private void registerConnector(OAuth2Connector connector) {
        when(connectorRegistry.findOAuth2(connector.getId())).thenReturn(Optional.of(connector));
        when(environment.getProperty(connector.clientIdProperty(), "")).thenReturn("client-id");
        when(environment.getProperty(connector.clientSecretProperty(), "")).thenReturn("client-secret");
    }

    private String stubState(String connectorId) {
        IntegrationOAuthState oauthState = new IntegrationOAuthState();
        oauthState.setState("state-1");
        oauthState.setProjectId(PROJECT_ID);
        oauthState.setConnectorId(connectorId);
        oauthState.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        when(oAuthStateRepository.findById("state-1")).thenReturn(Optional.of(oauthState));
        return "state-1";
    }

    private static Connection row(String id, String connectorId, OffsetDateTime createdAt, String configJson) {
        Connection conn = new Connection();
        conn.setId(id);
        conn.setProjectId(PROJECT_ID);
        conn.setConnectorId(connectorId);
        conn.setStatus("ACTIVE");
        conn.setCreatedAt(createdAt);
        conn.setConfigJson(configJson);
        return conn;
    }

    /** Makes {@code create} mint the fresh row the callback works on, and lists it with the others. */
    private Connection stubCreate(String connectorId, OffsetDateTime createdAt) {
        Connection fresh = row("conn-fresh", connectorId, createdAt, null);
        rows.add(fresh);
        when(connectionService.create(eq(PROJECT_ID), eq(connectorId), eq(AuthType.OAUTH2), anyString(), any()))
                .thenReturn(fresh);
        return fresh;
    }

    private void stubParkedGrant(Connection parked) {
        when(connectionService.decrypt(parked))
                .thenReturn(new DecryptedCredentials("user-token", "refresh-a", null, Map.of()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubTokenExchange(String accessToken, String refreshToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", accessToken);
        body.put("refresh_token", refreshToken);
        body.put("expires_in", 3600);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(body));
    }
}
