package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.IntegrationOAuthState;
import com.conductor.exception.BusinessException;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.OAuth2Connector;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
    private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

    /** Fallback scopes when a connector predates the {@link OAuth2Connector} scope declaration. */
    private static final List<String> DEFAULT_GOOGLE_SCOPES = List.of(
            "https://www.googleapis.com/auth/bigquery.readonly",
            "https://www.googleapis.com/auth/cloudplatformprojects.readonly");

    private final IntegrationOAuthStateRepository oAuthStateRepository;
    private final ConnectionService connectionService;
    private final ConnectorRegistry connectorRegistry;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${GOOGLE_OAUTH_CLIENT_ID:}")
    private String googleClientId;

    @Value("${GOOGLE_OAUTH_CLIENT_SECRET:}")
    private String googleClientSecret;

    @Value("${FRONTEND_URL:http://localhost:3000}")
    private String frontendUrl;

    @Value("${BACKEND_URL:}")
    private String backendUrl;

    public OAuthFlowService(IntegrationOAuthStateRepository oAuthStateRepository,
                            ConnectionService connectionService,
                            ConnectorRegistry connectorRegistry,
                            ObjectMapper objectMapper) {
        this.oAuthStateRepository = oAuthStateRepository;
        this.connectionService = connectionService;
        this.connectorRegistry = connectorRegistry;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    /** Scopes for the consent URL: the connector's own declaration, else the legacy default. */
    private List<String> scopesFor(String connectorId) {
        return connectorRegistry.findOAuth2(connectorId)
                .map(OAuth2Connector::oauthScopes)
                .filter(scopes -> !scopes.isEmpty())
                .orElse(DEFAULT_GOOGLE_SCOPES);
    }

    private void requireOAuthConfig() {
        if (googleClientId == null || googleClientId.isBlank()) {
            throw new IllegalStateException("GOOGLE_OAUTH_CLIENT_ID is not configured");
        }
        if (googleClientSecret == null || googleClientSecret.isBlank()) {
            throw new IllegalStateException("GOOGLE_OAUTH_CLIENT_SECRET is not configured");
        }
    }

    public String oauthCallbackUri() {
        return backendUrl + "/api/v1/oauth/callback";
    }

    @Transactional
    public String buildAuthorizationUrl(String projectId, String connectorId, String redirectUri) {
        requireOAuthConfig();
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
        return UriComponentsBuilder.fromUriString(GOOGLE_AUTH_URL)
                .queryParam("client_id", googleClientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", scopes)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .build().toUriString();
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

        Map<String, Object> tokenResponse = exchangeCodeForTokens(code, redirectUri);

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

    public String refreshAccessToken(Connection conn, String refreshToken) {
        requireOAuthConfig();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "refresh_token");
        params.add("refresh_token", refreshToken);
        params.add("client_id", googleClientId);
        params.add("client_secret", googleClientSecret);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
        ResponseEntity<Map> response = restTemplate.exchange(GOOGLE_TOKEN_URL, HttpMethod.POST, request, Map.class);

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
        log.info("Access token refreshed for connection={}", conn.getId());
        return newAccessToken;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCodeForTokens(String code, String redirectUri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("code", code);
        params.add("redirect_uri", redirectUri);
        params.add("client_id", googleClientId);
        params.add("client_secret", googleClientSecret);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
        ResponseEntity<Map> response = restTemplate.exchange(GOOGLE_TOKEN_URL, HttpMethod.POST, request, Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("access_token")) {
            throw new com.conductor.exception.BusinessException("Token exchange failed: no access_token in response");
        }
        return body;
    }
}
