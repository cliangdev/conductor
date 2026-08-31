package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.IntegrationOAuthState;
import com.conductor.exception.BusinessException;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.OAuth2Connector;
import com.conductor.integration.connector.gsc.GscConnector;
import com.conductor.integration.connector.local.LocalMetaConnector;
import com.conductor.integration.connector.local.LocalTikTokConnector;
import com.conductor.integration.connector.local.LocalYouTubeConnector;
import com.conductor.repository.ConnectorAppCredentialRepository;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.conductor.workflow.WorkflowSecretsEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The offline authorization seam: a connector may declare that it authorizes without a provider —
 * no client credentials, no code-for-token HTTP call — so the browser round trip a developer needs
 * to exercise the marketing pipeline completes on a laptop while platform App Review is pending.
 *
 * <p>Two halves, and the second matters more than the first. The offline half proves the local stubs
 * complete authorize → callback → connection with the network untouched, <b>on the real code path</b>:
 * the same state row, the same completion hook, the same account picker, the same {@code storeTokens}.
 * The unchanged half proves a connector that does not opt in is byte-for-byte what it was — the
 * consent URL and the token-exchange request are pinned as exact values, not as "contains" fragments,
 * because this seam is only safe if it is invisible to every real connector.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfflineOAuthFlowTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String REDIRECT_URI = "http://localhost:8080/api/v1/oauth/callback";

    @Mock private IntegrationOAuthStateRepository oAuthStateRepository;
    @Mock private ConnectionService connectionService;
    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private Environment environment;
    @Mock private ConnectionHealthService connectionHealthService;
    @Mock private RestTemplate restTemplate;
    @Mock private ConnectorAppCredentialRepository appCredentialRepository;
    @Mock private ProjectSecurityService projectSecurityService;

    private OAuthFlowService service;

    @BeforeEach
    void setUp() {
        ConnectorAppCredentialService appCredentialService = new ConnectorAppCredentialService(
                appCredentialRepository,
                new WorkflowSecretsEncryptionService("dGVzdC1zZWNyZXRzLWtleS0zMi1jaGFycy1wYWRkZWQ="),
                environment, projectSecurityService);
        service = new OAuthFlowService(oAuthStateRepository, connectionService, connectorRegistry,
                appCredentialService, new ObjectMapper(), connectionHealthService);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:3000");
    }

    // ---------------------------------------------------------------------------------------------
    // [auto] Offline authorization requires no client id or secret
    // ---------------------------------------------------------------------------------------------

    @Test
    void anOfflineConnectorAuthorizesWithNoClientIdOrSecretConfigured() {
        LocalMetaConnector meta = registerWithNoCredentials(new LocalMetaConnector());

        String url = service.buildAuthorizationUrl(PROJECT_ID, meta.getId(), REDIRECT_URI);

        assertThat(url).isNotBlank();
        // Nothing was read from the deployment env, and nothing was looked up per project.
        verifyNoInteractions(appCredentialRepository);
    }

    @Test
    void theOfflineAuthorizationUrlLandsStraightBackOnTheCallbackWithASyntheticCode() {
        LocalMetaConnector meta = registerWithNoCredentials(new LocalMetaConnector());

        String url = service.buildAuthorizationUrl(PROJECT_ID, meta.getId(), REDIRECT_URI);

        assertThat(url).startsWith(REDIRECT_URI + "?");
        assertThat(url).doesNotContain("facebook.com");
        assertThat(queryParam(url, "code")).isNotBlank();
        assertThat(queryParam(url, "state")).isEqualTo(savedState());
    }

    // ---------------------------------------------------------------------------------------------
    // [auto] The full authorize -> callback -> connection-created round trip completes with no
    //        network call
    // ---------------------------------------------------------------------------------------------

    @Test
    void theOfflineRoundTripCreatesAnActiveConnectionWithoutAnyHttpCall() {
        LocalYouTubeConnector youtube = registerWithNoCredentials(new LocalYouTubeConnector());
        List<Connection> created = stubCreate(youtube.getId());

        String authorizationUrl = service.buildAuthorizationUrl(PROJECT_ID, youtube.getId(), REDIRECT_URI);
        String redirect = followCallback(authorizationUrl);

        assertThat(created).hasSize(1);
        assertThat(created.get(0).getStatus()).isEqualTo("ACTIVE");
        assertThat(redirect).isEqualTo(
                "http://localhost:3000/app/projects/proj-1/integrations/youtube");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void theOfflineRoundTripStoresTokensAndTheStubsOwnConfigKeys() {
        LocalYouTubeConnector youtube = registerWithNoCredentials(new LocalYouTubeConnector());
        List<Connection> created = stubCreate(youtube.getId());

        followCallback(service.buildAuthorizationUrl(PROJECT_ID, youtube.getId(), REDIRECT_URI));

        verify(connectionService).storeTokens(eq(created.get(0)), anyString(), any(), any());
        ArgumentCaptor<Map<String, Object>> configCaptor = configCaptor();
        verify(connectionService).updateConfig(eq(created.get(0)), configCaptor.capture());
        // The stub's own completion hook ran — this is the real code path, not a bypass.
        assertThat(configCaptor.getValue())
                .containsAllEntriesOf(youtube.completeAuthorization(
                        new OAuth2Connector.OAuthCompletionRequest("t", null, null)).config());
    }

    @Test
    void theLocalTikTokStubAlsoCompletesOffline() {
        LocalTikTokConnector tiktok = registerWithNoCredentials(new LocalTikTokConnector());
        List<Connection> created = stubCreate(tiktok.getId());

        String redirect = followCallback(
                service.buildAuthorizationUrl(PROJECT_ID, tiktok.getId(), REDIRECT_URI));

        assertThat(created).hasSize(1);
        assertThat(redirect).isEqualTo("http://localhost:3000/app/projects/proj-1/integrations/tiktok");
        verifyNoInteractions(restTemplate);
        ArgumentCaptor<Map<String, Object>> configCaptor = configCaptor();
        verify(connectionService).updateConfig(eq(created.get(0)), configCaptor.capture());
        assertThat(configCaptor.getValue()).isNotEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // [auto] The Meta stub's account-picker redirect still fires, and selecting a Page still
    //        finalizes the connection
    // ---------------------------------------------------------------------------------------------

    @Test
    void theMetaStubStillParksTheConnectionAndRedirectsToTheAccountPicker() {
        LocalMetaConnector meta = registerWithNoCredentials(new LocalMetaConnector());
        List<Connection> created = stubCreate(meta.getId());

        String redirect = followCallback(
                service.buildAuthorizationUrl(PROJECT_ID, meta.getId(), REDIRECT_URI));

        assertThat(redirect).isEqualTo("http://localhost:3000/app/projects/proj-1/integrations/meta"
                + "?selectAccount=" + created.get(0).getId());
        // Parked, not finalized: the grant is stored so the picker can enumerate against it.
        verify(connectionService).storeTokens(eq(created.get(0)), anyString(), any(), any());
        verify(connectionService, never()).updateConfig(any(), any());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void selectingAPageOnTheMetaStubFinalizesTheConnectionWithThatPagesConfig() {
        LocalMetaConnector meta = registerWithNoCredentials(new LocalMetaConnector());
        List<Connection> created = stubCreate(meta.getId());

        followCallback(service.buildAuthorizationUrl(PROJECT_ID, meta.getId(), REDIRECT_URI));

        Connection conn = created.get(0);
        when(connectionService.decrypt(conn))
                .thenReturn(new DecryptedCredentials("stub-access", "stub-refresh", null, Map.of()));

        List<OAuth2Connector.OAuthAccount> accounts = service.listAuthorizableAccounts(conn);
        assertThat(accounts).isNotEmpty();

        String pageId = accounts.get(0).id();
        service.completeAccountSelection(conn, pageId);

        ArgumentCaptor<Map<String, Object>> configCaptor = configCaptor();
        verify(connectionService).updateConfig(eq(conn), configCaptor.capture());
        assertThat(configCaptor.getValue()).containsEntry("pageId", pageId);
        verify(connectionService).updateLabel(eq(conn), eq(accounts.get(0).label()));
        verifyNoInteractions(restTemplate);
    }

    // ---------------------------------------------------------------------------------------------
    // [auto] CSRF protection is unchanged: state is still generated, stored and validated
    // ---------------------------------------------------------------------------------------------

    @Test
    void offlineAuthorizationStillStoresTheStateRowForTheProjectAndConnector() {
        LocalMetaConnector meta = registerWithNoCredentials(new LocalMetaConnector());

        service.buildAuthorizationUrl(PROJECT_ID, meta.getId(), REDIRECT_URI);

        IntegrationOAuthState saved = savedStateRow();
        assertThat(saved.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(saved.getConnectorId()).isEqualTo("meta");
        assertThat(saved.getState()).hasSize(32);
        assertThat(saved.getExpiresAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    void aTamperedStateIsStillRejectedForAnOfflineConnector() {
        registerWithNoCredentials(new LocalMetaConnector());
        when(oAuthStateRepository.findById("tampered")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleCallback("any-code", "tampered", REDIRECT_URI))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid or expired OAuth state");

        verify(connectionService, never()).storeTokens(any(), anyString(), anyString(), any());
    }

    @Test
    void anExpiredStateIsStillRejectedForAnOfflineConnector() {
        registerWithNoCredentials(new LocalMetaConnector());
        IntegrationOAuthState expired = new IntegrationOAuthState();
        expired.setState("stale");
        expired.setProjectId(PROJECT_ID);
        expired.setConnectorId("meta");
        expired.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(oAuthStateRepository.findById("stale")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.handleCallback("any-code", "stale", REDIRECT_URI))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");

        verify(oAuthStateRepository).delete(expired);
        verify(connectionService, never()).storeTokens(any(), anyString(), anyString(), any());
    }

    // ---------------------------------------------------------------------------------------------
    // [auto] A non-opting connector's consent URL and token-exchange request are byte-for-byte
    //        unchanged
    // ---------------------------------------------------------------------------------------------

    @Test
    void aNonOptingConnectorsConsentUrlIsByteForByteUnchanged() {
        GscConnector gsc = new GscConnector();
        registerConnector(gsc, "google-client-id", "google-client-secret");

        String url = service.buildAuthorizationUrl(PROJECT_ID, "gsc", REDIRECT_URI);

        assertThat(url).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=google-client-id"
                + "&redirect_uri=" + REDIRECT_URI
                + "&response_type=code"
                + "&scope=https://www.googleapis.com/auth/webmasters.readonly"
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + savedState());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aNonOptingConnectorsTokenExchangeRequestIsByteForByteUnchanged() {
        GscConnector gsc = new GscConnector();
        registerConnector(gsc, "google-client-id", "google-client-secret");
        when(connectionService.getOrCreateSingle(PROJECT_ID, "gsc", AuthType.OAUTH2))
                .thenReturn(connection("conn-gsc", "gsc"));
        ArgumentCaptor<HttpEntity> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), requestCaptor.capture(), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(
                        Map.of("access_token", "google-access", "expires_in", 3600)));

        service.handleCallback("code-1", stubState("gsc", "state-1"), REDIRECT_URI);

        verify(restTemplate).exchange(eq("https://oauth2.googleapis.com/token"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(Map.class));
        HttpEntity<?> request = requestCaptor.getValue();
        assertThat(request.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
        assertThat(formBody(request)).containsExactly(
                entry("grant_type", "authorization_code"),
                entry("code", "code-1"),
                entry("redirect_uri", REDIRECT_URI),
                entry("client_id", "google-client-id"),
                entry("client_secret", "google-client-secret"));
    }

    @Test
    void aNonOptingConnectorStillRequiresCredentialsWithTheIdenticalMessage() {
        GscConnector gsc = new GscConnector();
        when(connectorRegistry.findOAuth2("gsc")).thenReturn(Optional.of(gsc));
        when(environment.getProperty("GOOGLE_OAUTH_CLIENT_ID", "")).thenReturn("");
        when(environment.getProperty("GOOGLE_OAUTH_CLIENT_SECRET", "")).thenReturn("");

        assertThatThrownBy(() -> service.buildAuthorizationUrl(PROJECT_ID, "gsc", REDIRECT_URI))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth client credentials not configured: GOOGLE_OAUTH_CLIENT_ID");

        verify(oAuthStateRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------------
    // [auto] The opt-in belongs to the connector implementation, and only the local stubs take it
    // ---------------------------------------------------------------------------------------------

    @Test
    void noConnectorOptsIntoOfflineAuthorizationByDefault() {
        assertThat(new GscConnector().usesStubAuthorization()).isFalse();
        assertThat(new PlainConnector().usesStubAuthorization()).isFalse();
    }

    @Test
    void theThreeLocalSocialStubsOptIn() {
        assertThat(new LocalMetaConnector().usesStubAuthorization()).isTrue();
        assertThat(new LocalYouTubeConnector().usesStubAuthorization()).isTrue();
        assertThat(new LocalTikTokConnector().usesStubAuthorization()).isTrue();
    }

    /**
     * The production guard. Offline authorization can only be switched on by a connector class
     * overriding the hook — there is no property, no env var and no bean that flips it — so this scan
     * over every OAuth2 connector on the classpath is sufficient to prove that no deployment
     * configuration can reach it: every overriding class is confined to the {@code local} profile.
     */
    @Test
    void onlyLocalProfileConnectorsOverrideTheOfflineAuthorizationHook() {
        List<String> overriders = new ArrayList<>();
        List<String> offenders = new ArrayList<>();

        for (Class<?> clazz : everyOAuth2ConnectorBean()) {
            Method hook;
            try {
                hook = clazz.getMethod("usesStubAuthorization");
            } catch (NoSuchMethodException e) {
                continue;
            }
            if (hook.getDeclaringClass().equals(OAuth2Connector.class)) {
                continue;
            }
            overriders.add(clazz.getName());
            Profile profile = clazz.getAnnotation(Profile.class);
            if (profile == null || !List.of(profile.value()).equals(List.of("local"))) {
                offenders.add(clazz.getName()
                        + " overrides usesStubAuthorization() but is not @Profile(\"local\")");
            }
        }

        assertThat(offenders).isEmpty();
        assertThat(overriders).containsExactlyInAnyOrder(
                LocalMetaConnector.class.getName(),
                LocalYouTubeConnector.class.getName(),
                LocalTikTokConnector.class.getName());
    }

    /**
     * Every OAuth2 connector bean in the codebase, on both sides of the {@code local} profile split.
     * Scanned twice on purpose: the component provider evaluates {@code @Profile} against its own
     * environment, so a single pass silently omits half the connectors — and the half it omits is the
     * one this guard exists to check.
     */
    private static List<Class<?>> everyOAuth2ConnectorBean() {
        Set<Class<?>> found = new LinkedHashSet<>();
        for (String[] activeProfiles : List.of(new String[]{"local"}, new String[0])) {
            StandardEnvironment env = new StandardEnvironment();
            env.setActiveProfiles(activeProfiles);
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false, env);
            scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
            for (BeanDefinition bd : scanner.findCandidateComponents("com.conductor.integration")) {
                String className = bd.getBeanClassName();
                if (className == null) {
                    continue;
                }
                try {
                    Class<?> clazz = Class.forName(className);
                    if (OAuth2Connector.class.isAssignableFrom(clazz)) {
                        found.add(clazz);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    // Not loadable from the test classpath — nothing to assert about it.
                }
            }
        }
        assertThat(found).as("the scan found OAuth2 connectors at all").isNotEmpty();
        return List.copyOf(found);
    }

    // ---- fixtures -------------------------------------------------------------------------------

    /** A connector that overrides nothing, to pin the interface default. */
    private static class PlainConnector implements OAuth2Connector {
        @Override public String getId() { return "plain"; }
        @Override public List<String> oauthScopes() { return List.of("plain.read"); }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("plain", "Plain", ConnectorCategory.ANALYTICS, "Plain", "PL");
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(true, List.of());
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Registers an offline connector with the deployment env holding no credentials whatsoever. */
    private <T extends OAuth2Connector> T registerWithNoCredentials(T connector) {
        when(connectorRegistry.findOAuth2(connector.getId())).thenReturn(Optional.of(connector));
        when(environment.getProperty(anyString(), anyString())).thenReturn("");
        return connector;
    }

    private void registerConnector(OAuth2Connector connector, String clientId, String clientSecret) {
        when(connectorRegistry.findOAuth2(connector.getId())).thenReturn(Optional.of(connector));
        when(environment.getProperty(connector.clientIdProperty(), "")).thenReturn(clientId);
        when(environment.getProperty(connector.clientSecretProperty(), "")).thenReturn(clientSecret);
    }

    /**
     * Walks the browser hop the offline authorization URL describes: reads its {@code code} and
     * {@code state} straight back out and hands them to the callback, exactly as the browser would.
     */
    private String followCallback(String authorizationUrl) {
        String state = queryParam(authorizationUrl, "state");
        IntegrationOAuthState row = savedStateRow();
        when(oAuthStateRepository.findById(state)).thenReturn(Optional.of(row));
        return service.handleCallback(queryParam(authorizationUrl, "code"), state, REDIRECT_URI);
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

    private IntegrationOAuthState savedStateRow() {
        ArgumentCaptor<IntegrationOAuthState> captor = ArgumentCaptor.forClass(IntegrationOAuthState.class);
        verify(oAuthStateRepository).save(captor.capture());
        return captor.getValue();
    }

    private String savedState() {
        return savedStateRow().getState();
    }

    private static String queryParam(String url, String name) {
        List<String> values = UriComponentsBuilder.fromUriString(url).build().getQueryParams().get(name);
        assertThat(values).as("query param %s of %s", name, url).isNotNull().isNotEmpty();
        return values.get(0);
    }

    private static Connection connection(String id, String connectorId) {
        Connection conn = new Connection();
        conn.setId(id);
        conn.setProjectId(PROJECT_ID);
        conn.setConnectorId(connectorId);
        conn.setStatus("ACTIVE");
        return conn;
    }

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
