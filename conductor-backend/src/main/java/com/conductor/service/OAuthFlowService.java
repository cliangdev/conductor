package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.IntegrationOAuthState;
import com.conductor.exception.BusinessException;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.OAuth2Connector;
import com.conductor.integration.OAuthReauthRequiredException;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class OAuthFlowService {

    private static final Logger log = LoggerFactory.getLogger(OAuthFlowService.class);

    /** Fallback scopes when a connector predates the {@link OAuth2Connector} scope declaration. */
    private static final List<String> DEFAULT_GOOGLE_SCOPES = List.of(
            "https://www.googleapis.com/auth/bigquery.readonly",
            "https://www.googleapis.com/auth/cloudplatformprojects.readonly");

    private final IntegrationOAuthStateRepository oAuthStateRepository;
    private final ConnectionService connectionService;
    private final ConnectorRegistry connectorRegistry;
    private final Environment environment;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ConnectionHealthService connectionHealthService;

    @Value("${FRONTEND_URL:http://localhost:3000}")
    private String frontendUrl;

    @Value("${BACKEND_URL:}")
    private String backendUrl;

    public OAuthFlowService(IntegrationOAuthStateRepository oAuthStateRepository,
                            ConnectionService connectionService,
                            ConnectorRegistry connectorRegistry,
                            Environment environment,
                            ObjectMapper objectMapper,
                            ConnectionHealthService connectionHealthService) {
        this.oAuthStateRepository = oAuthStateRepository;
        this.connectionService = connectionService;
        this.connectorRegistry = connectorRegistry;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.connectionHealthService = connectionHealthService;
        this.restTemplate = new RestTemplate();
    }

    /** Scopes for the consent URL: the connector's own declaration, else the legacy default. */
    private List<String> scopesFor(String connectorId) {
        return connectorRegistry.findOAuth2(connectorId)
                .map(OAuth2Connector::oauthScopes)
                .filter(scopes -> !scopes.isEmpty())
                .orElse(DEFAULT_GOOGLE_SCOPES);
    }

    private OAuth2Connector requireOAuth2Connector(String connectorId) {
        return connectorRegistry.findOAuth2(connectorId)
                .orElseThrow(() -> new IllegalStateException(
                        "Connector does not support OAuth2: " + connectorId));
    }

    private record OAuthCredentials(String clientId, String clientSecret) {}

    private OAuthCredentials requireOAuthConfig(OAuth2Connector connector) {
        String clientId = environment.getProperty(connector.clientIdProperty(), "");
        if (clientId.isBlank()) {
            throw new IllegalStateException(
                    "OAuth client credentials not configured: " + connector.clientIdProperty());
        }
        String clientSecret = environment.getProperty(connector.clientSecretProperty(), "");
        if (clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "OAuth client credentials not configured: " + connector.clientSecretProperty());
        }
        return new OAuthCredentials(clientId, clientSecret);
    }

    public String oauthCallbackUri() {
        return backendUrl + "/api/v1/oauth/callback";
    }

    @Transactional
    public String buildAuthorizationUrl(String projectId, String connectorId, String redirectUri) {
        OAuth2Connector connector = requireOAuth2Connector(connectorId);
        OAuthCredentials creds = requireOAuthConfig(connector);
        oAuthStateRepository.deleteByExpiresAtBefore(OffsetDateTime.now());

        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);

        IntegrationOAuthState oauthState = new IntegrationOAuthState();
        oauthState.setState(state);
        oauthState.setProjectId(projectId);
        oauthState.setConnectorId(connectorId);
        oauthState.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        oauthState.setConfigJson(Map.of());
        oAuthStateRepository.save(oauthState);

        String scopes = String.join(" ", scopesFor(connectorId));
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(connector.authorizationUrl())
                .queryParam("client_id", creds.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", scopes);
        connector.extraAuthorizationParams().forEach(builder::queryParam);
        builder.queryParam("state", state);
        return builder.build().toUriString();
    }

    @Transactional
    public String handleCallback(String code, String state, String redirectUri) {
        IntegrationOAuthState oauthState = oAuthStateRepository.findById(state)
                .orElseThrow(() -> new BusinessException("Invalid or expired OAuth state"));

        if (oauthState.getExpiresAt().isBefore(OffsetDateTime.now())) {
            oAuthStateRepository.delete(oauthState);
            throw new BusinessException("OAuth state has expired");
        }

        String projectId = oauthState.getProjectId();
        String connectorId = oauthState.getConnectorId();

        Map<String, Object> tokenResponse = exchangeCodeForTokens(connectorId, code, redirectUri);

        String accessToken = (String) tokenResponse.get("access_token");
        String refreshToken = (String) tokenResponse.get("refresh_token");
        Object expiresIn = tokenResponse.get("expires_in");
        OffsetDateTime expiresAt = expiresIn != null
                ? OffsetDateTime.now().plusSeconds(((Number) expiresIn).longValue())
                : null;

        Connection conn = connectionService.getOrCreateSingle(projectId, connectorId, AuthType.OAUTH2);
        connectionService.storeTokens(conn, accessToken, refreshToken, expiresAt);

        oAuthStateRepository.delete(oauthState);

        log.info("OAuth callback completed for connector={} project={}", connectorId, projectId);
        return frontendUrl + "/app/projects/" + projectId + "/integrations/" + connectorId;
    }

    /**
     * Refreshes the access token for an existing connection. Resolves the connector from the
     * connection's connectorId — a connection whose connector doesn't support OAuth2 attempting a
     * refresh indicates a bug elsewhere, so this fails loudly rather than silently.
     */
    public String refreshAccessToken(Connection conn, String refreshToken) {
        OAuth2Connector connector = connectorRegistry.findOAuth2(conn.getConnectorId())
                .orElseThrow(() -> new IllegalStateException(
                        "Connector does not support OAuth2 refresh: " + conn.getConnectorId()));
        OAuthCredentials creds = requireOAuthConfig(connector);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "refresh_token");
        params.add("refresh_token", refreshToken);
        params.add("client_id", creds.clientId());
        params.add("client_secret", creds.clientSecret());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
        ResponseEntity<Map> response;
        try {
            response = restTemplate.exchange(connector.tokenUrl(), HttpMethod.POST, request, Map.class);
        } catch (HttpClientErrorException e) {
            // Only a permanent auth rejection costs the connection its health. A rate limit, a 5xx,
            // or a network blip (which never reaches this catch at all) is transient: retrying the
            // same credentials can still succeed, so the connection stays as healthy as it was.
            if (isAuthFailure(e)) {
                connectionHealthService.markUnhealthy(conn.getId(), providerMessage(e));
            }
            if (isInvalidGrant(e)) {
                throw new OAuthReauthRequiredException(
                        "Refresh token for connection " + conn.getId() + " is no longer valid ("
                                + e.getStatusCode() + ") — the user must reconnect via OAuth", e);
            }
            throw e;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("access_token")) {
            throw new BusinessException("Token refresh failed: no access_token in response");
        }

        String newAccessToken = (String) body.get("access_token");
        Object expiresIn = body.get("expires_in");
        OffsetDateTime newExpiresAt = expiresIn != null
                ? OffsetDateTime.now().plusSeconds(((Number) expiresIn).longValue())
                : null;

        connectionService.updateAccessToken(conn, newAccessToken, newExpiresAt);
        // The provider just honoured these credentials, which is the strongest health signal there
        // is — so a connection previously marked unhealthy clears itself without human intervention.
        connectionHealthService.markHealthy(conn.getId());
        log.info("Access token refreshed for connection={}", conn.getId());
        return newAccessToken;
    }

    /** Google (and most providers) return 400 {@code invalid_grant} both for a genuinely
     * expired/revoked refresh token and, rarely, for a malformed request — either way, retrying the
     * same refresh token will never succeed. */
    private boolean isInvalidGrant(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        return body != null && body.contains("invalid_grant");
    }

    /**
     * Whether the provider rejected who we are rather than failing for a transient reason: a 401/403
     * outright, or one of the OAuth error codes that mean the grant or the client is no longer
     * usable. Anything else (429, 5xx, a network error) is transient by default — the safe direction
     * to be wrong in, since a false UNHEALTHY tells a human to reconnect a connection that is fine.
     */
    private boolean isAuthFailure(HttpClientErrorException e) {
        if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
            return true;
        }
        String body = e.getResponseBodyAsString();
        return body != null && (body.contains("invalid_grant")
                || body.contains("invalid_client")
                || body.contains("unauthorized_client")
                || body.contains("invalid_token"));
    }

    /** The provider's own explanation if it gave one, so the UI can show a human what went wrong. */
    private String providerMessage(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            try {
                Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
                Object description = parsed.get("error_description");
                Object error = parsed.get("error");
                if (description != null) {
                    return String.valueOf(description);
                }
                if (error != null) {
                    return String.valueOf(error);
                }
            } catch (Exception ignored) {
                // Not JSON, or not the shape we expect — fall through to the raw status line.
            }
        }
        return "The provider rejected this connection's credentials (" + e.getStatusCode()
                + "). Reconnect the account.";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCodeForTokens(String connectorId, String code, String redirectUri) {
        OAuth2Connector connector = requireOAuth2Connector(connectorId);
        OAuthCredentials creds = requireOAuthConfig(connector);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("code", code);
        params.add("redirect_uri", redirectUri);
        params.add("client_id", creds.clientId());
        params.add("client_secret", creds.clientSecret());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
        ResponseEntity<Map> response = restTemplate.exchange(connector.tokenUrl(), HttpMethod.POST, request, Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("access_token")) {
            throw new com.conductor.exception.BusinessException("Token exchange failed: no access_token in response");
        }
        return body;
    }
}
