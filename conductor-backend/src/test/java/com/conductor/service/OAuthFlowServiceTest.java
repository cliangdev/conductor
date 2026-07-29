package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.IntegrationOAuthState;
import com.conductor.exception.BusinessException;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.OAuth2Connector;
import com.conductor.integration.OAuthReauthRequiredException;
import com.conductor.integration.connector.gsc.GscConnector;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthFlowServiceTest {

    @Mock
    private IntegrationOAuthStateRepository oAuthStateRepository;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ConnectorRegistry connectorRegistry;

    @Mock
    private Environment environment;

    private OAuthFlowService service;

    private static final String PROJECT_ID = "proj-1";
    private static final String CONNECTOR_ID = "gcp-billing";
    private static final String REDIRECT_URI = "http://localhost:8080/api/v1/oauth/callback";

    @BeforeEach
    void setUp() {
        service = new OAuthFlowService(oAuthStateRepository, connectionService, connectorRegistry, environment, new ObjectMapper());
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:3000");
    }

    /** A non-Google OAuth2 connector overriding every default, to prove the flow is connector-driven. */
    private static class FakeOAuth2Connector implements OAuth2Connector {
        @Override
        public String getId() { return "acme"; }

        @Override
        public List<String> oauthScopes() { return List.of("acme.read"); }

        @Override
        public String authorizationUrl() { return "https://acme.example.com/oauth/authorize"; }

        @Override
        public String tokenUrl() { return "https://acme.example.com/oauth/token"; }

        @Override
        public String clientIdProperty() { return "ACME_OAUTH_CLIENT_ID"; }

        @Override
        public String clientSecretProperty() { return "ACME_OAUTH_CLIENT_SECRET"; }

        @Override
        public Map<String, String> extraAuthorizationParams() {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("audience", "acme-api");
            return params;
        }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("acme", "Acme", com.conductor.integration.ConnectorCategory.ANALYTICS, "Acme", "AC");
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(true, List.of());
        }
    }

    @Test
    void buildAuthorizationUrlComposesFromConnectorForNonGoogleProvider() {
        FakeOAuth2Connector connector = new FakeOAuth2Connector();
        when(connectorRegistry.findOAuth2("acme")).thenReturn(Optional.of(connector));
        when(environment.getProperty("ACME_OAUTH_CLIENT_ID", "")).thenReturn("acme-client-id");
        when(environment.getProperty("ACME_OAUTH_CLIENT_SECRET", "")).thenReturn("acme-client-secret");

        String url = service.buildAuthorizationUrl(PROJECT_ID, "acme", REDIRECT_URI);

        ArgumentCaptor<IntegrationOAuthState> captor = ArgumentCaptor.forClass(IntegrationOAuthState.class);
        verify(oAuthStateRepository).save(captor.capture());
        String state = captor.getValue().getState();

        assertThat(url).startsWith("https://acme.example.com/oauth/authorize?");
        assertThat(url).contains("client_id=acme-client-id");
        assertThat(url).contains("redirect_uri=" + REDIRECT_URI);
        assertThat(url).contains("scope=acme.read");
        assertThat(url).contains("audience=acme-api");
        assertThat(url).contains("state=" + state);
        assertThat(url).doesNotContain("access_type");
        assertThat(url).doesNotContain("prompt=consent");
    }

    @Test
    void buildAuthorizationUrlMissingClientIdNamesTheProperty() {
        FakeOAuth2Connector connector = new FakeOAuth2Connector();
        when(connectorRegistry.findOAuth2("acme")).thenReturn(Optional.of(connector));
        when(environment.getProperty("ACME_OAUTH_CLIENT_ID", "")).thenReturn("");

        assertThatThrownBy(() -> service.buildAuthorizationUrl(PROJECT_ID, "acme", REDIRECT_URI))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACME_OAUTH_CLIENT_ID");

        verify(oAuthStateRepository, never()).save(any());
    }

    @Test
    void buildAuthorizationUrlUnknownConnectorThrows() {
        when(connectorRegistry.findOAuth2("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buildAuthorizationUrl(PROJECT_ID, "nope", REDIRECT_URI))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void handleCallbackExchangesCodeAgainstTheConnectorsTokenUrlAndCreds() {
        FakeOAuth2Connector connector = new FakeOAuth2Connector();
        when(connectorRegistry.findOAuth2("acme")).thenReturn(Optional.of(connector));
        when(environment.getProperty("ACME_OAUTH_CLIENT_ID", "")).thenReturn("acme-client-id");
        when(environment.getProperty("ACME_OAUTH_CLIENT_SECRET", "")).thenReturn("acme-client-secret");

        String state = "validstate";
        IntegrationOAuthState oauthState = new IntegrationOAuthState();
        oauthState.setState(state);
        oauthState.setProjectId(PROJECT_ID);
        oauthState.setConnectorId("acme");
        oauthState.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        when(oAuthStateRepository.findById(state)).thenReturn(Optional.of(oauthState));

        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setProjectId(PROJECT_ID);
        conn.setConnectorId("acme");
        when(connectionService.getOrCreateSingle(PROJECT_ID, "acme", AuthType.OAUTH2)).thenReturn(conn);

        Map<String, Object> tokenResponse = Map.of(
                "access_token", "access-123",
                "refresh_token", "refresh-456",
                "expires_in", 3600);
        ArgumentCaptor<HttpEntity> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq("https://acme.example.com/oauth/token"), eq(HttpMethod.POST),
                requestCaptor.capture(), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(tokenResponse));

        String redirect = service.handleCallback("auth-code", state, REDIRECT_URI);

        MultiValueMapAssertHelper.assertContains(requestCaptor.getValue(), "client_id", "acme-client-id");
        MultiValueMapAssertHelper.assertContains(requestCaptor.getValue(), "client_secret", "acme-client-secret");

        verify(oAuthStateRepository).delete(oauthState);
        verify(connectionService).storeTokens(
                eq(conn), eq("access-123"), eq("refresh-456"), any(OffsetDateTime.class));
        assertThat(redirect).isEqualTo(
                "http://localhost:3000/app/projects/proj-1/integrations/acme");
    }

    @Test
    void handleCallbackWithUnknownStateThrowsBadRequest() {
        when(oAuthStateRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleCallback("auth-code", "missing", REDIRECT_URI))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid or expired OAuth state");

        verify(connectionService, never()).storeTokens(any(), anyString(), anyString(), any());
    }

    @Test
    void handleCallbackWithExpiredStateThrowsBadRequestAndDeletesState() {
        String state = "expiredstate";
        IntegrationOAuthState oauthState = new IntegrationOAuthState();
        oauthState.setState(state);
        oauthState.setProjectId(PROJECT_ID);
        oauthState.setConnectorId(CONNECTOR_ID);
        oauthState.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(oAuthStateRepository.findById(state)).thenReturn(Optional.of(oauthState));

        assertThatThrownBy(() -> service.handleCallback("auth-code", state, REDIRECT_URI))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");

        verify(oAuthStateRepository).delete(oauthState);
        verify(connectionService, never()).storeTokens(any(), anyString(), anyString(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void refreshAccessTokenUsesTheConnectorsTokenUrlAndCreds() {
        FakeOAuth2Connector connector = new FakeOAuth2Connector();
        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setConnectorId("acme");
        when(connectorRegistry.findOAuth2("acme")).thenReturn(Optional.of(connector));
        when(environment.getProperty("ACME_OAUTH_CLIENT_ID", "")).thenReturn("acme-client-id");
        when(environment.getProperty("ACME_OAUTH_CLIENT_SECRET", "")).thenReturn("acme-client-secret");

        Map<String, Object> tokenResponse = Map.of("access_token", "new-access", "expires_in", 3600);
        ArgumentCaptor<HttpEntity> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq("https://acme.example.com/oauth/token"), eq(HttpMethod.POST),
                requestCaptor.capture(), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(tokenResponse));

        String newToken = service.refreshAccessToken(conn, "refresh-456");

        assertThat(newToken).isEqualTo("new-access");
        MultiValueMapAssertHelper.assertContains(requestCaptor.getValue(), "client_id", "acme-client-id");
        MultiValueMapAssertHelper.assertContains(requestCaptor.getValue(), "client_secret", "acme-client-secret");
        MultiValueMapAssertHelper.assertContains(requestCaptor.getValue(), "refresh_token", "refresh-456");
        verify(connectionService).updateAccessToken(eq(conn), eq("new-access"), any(OffsetDateTime.class));
    }

    @Test
    void refreshAccessTokenOnInvalidGrantThrowsReauthRequired() {
        FakeOAuth2Connector connector = new FakeOAuth2Connector();
        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setConnectorId("acme");
        when(connectorRegistry.findOAuth2("acme")).thenReturn(Optional.of(connector));
        when(environment.getProperty("ACME_OAUTH_CLIENT_ID", "")).thenReturn("acme-client-id");
        when(environment.getProperty("ACME_OAUTH_CLIENT_SECRET", "")).thenReturn("acme-client-secret");

        HttpClientErrorException invalidGrant = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                "{\"error\": \"invalid_grant\", \"error_description\": \"Token has been expired or revoked.\"}"
                        .getBytes(), null);
        when(restTemplate.exchange(eq("https://acme.example.com/oauth/token"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(Map.class)))
                .thenThrow(invalidGrant);

        assertThatThrownBy(() -> service.refreshAccessToken(conn, "refresh-456"))
                .isInstanceOf(OAuthReauthRequiredException.class)
                .hasMessageContaining("conn-1");
        verify(connectionService, never()).updateAccessToken(any(), any(), any());
    }

    @Test
    void refreshAccessTokenOnOtherClientErrorRethrowsAsIs() {
        FakeOAuth2Connector connector = new FakeOAuth2Connector();
        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setConnectorId("acme");
        when(connectorRegistry.findOAuth2("acme")).thenReturn(Optional.of(connector));
        when(environment.getProperty("ACME_OAUTH_CLIENT_ID", "")).thenReturn("acme-client-id");
        when(environment.getProperty("ACME_OAUTH_CLIENT_SECRET", "")).thenReturn("acme-client-secret");

        HttpClientErrorException rateLimited = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", new HttpHeaders(),
                "{\"error\": \"rate_limit_exceeded\"}".getBytes(), null);
        when(restTemplate.exchange(eq("https://acme.example.com/oauth/token"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(Map.class)))
                .thenThrow(rateLimited);

        // A transient failure must NOT be reclassified as a permanent reauth requirement — the
        // caller (IntegrationFetchService) treats these very differently.
        assertThatThrownBy(() -> service.refreshAccessToken(conn, "refresh-456"))
                .isInstanceOf(HttpClientErrorException.class)
                .isNotInstanceOf(OAuthReauthRequiredException.class);
    }

    @Test
    void refreshAccessTokenForConnectorWithoutOAuth2SupportThrows() {
        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setConnectorId("webhook-only");
        when(connectorRegistry.findOAuth2("webhook-only")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refreshAccessToken(conn, "refresh-456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook-only");
    }

    // ---- Regression lock: GscConnector (a real Google connector) still drives the Google endpoints ----

    @Test
    void buildAuthorizationUrlForGscConnectorHitsGoogleEndpointsWithGoogleParams() {
        GscConnector gsc = new GscConnector();
        when(connectorRegistry.findOAuth2("gsc")).thenReturn(Optional.of(gsc));
        when(environment.getProperty("GOOGLE_OAUTH_CLIENT_ID", "")).thenReturn("google-client-id");
        when(environment.getProperty("GOOGLE_OAUTH_CLIENT_SECRET", "")).thenReturn("google-client-secret");

        String url = service.buildAuthorizationUrl(PROJECT_ID, "gsc", REDIRECT_URI);

        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(url).contains("client_id=google-client-id");
        assertThat(url).contains("scope=https://www.googleapis.com/auth/webmasters.readonly");
        assertThat(url).contains("access_type=offline");
        assertThat(url).contains("prompt=consent");
        // access_type must precede prompt — matches the pre-generalization param order exactly.
        assertThat(url.indexOf("access_type")).isLessThan(url.indexOf("prompt=consent"));
        assertThat(gsc.tokenUrl()).isEqualTo("https://oauth2.googleapis.com/token");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void refreshAccessTokenForGscConnectorHitsGoogleTokenUrl() {
        GscConnector gsc = new GscConnector();
        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setConnectorId("gsc");
        when(connectorRegistry.findOAuth2("gsc")).thenReturn(Optional.of(gsc));
        when(environment.getProperty("GOOGLE_OAUTH_CLIENT_ID", "")).thenReturn("google-client-id");
        when(environment.getProperty("GOOGLE_OAUTH_CLIENT_SECRET", "")).thenReturn("google-client-secret");

        Map<String, Object> tokenResponse = Map.of("access_token", "new-access", "expires_in", 3600);
        when(restTemplate.exchange(eq("https://oauth2.googleapis.com/token"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(tokenResponse));

        String newToken = service.refreshAccessToken(conn, "refresh-456");

        assertThat(newToken).isEqualTo("new-access");
    }

    /** Small helper to assert on a captured form-encoded HttpEntity without repeating the cast everywhere. */
    private static final class MultiValueMapAssertHelper {
        @SuppressWarnings("unchecked")
        static void assertContains(HttpEntity<?> entity, String key, String expectedValue) {
            org.springframework.util.MultiValueMap<String, String> body =
                    (org.springframework.util.MultiValueMap<String, String>) entity.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getFirst(key)).isEqualTo(expectedValue);
        }
    }
}
