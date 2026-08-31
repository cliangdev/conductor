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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OAuthFlowService {

    private static final Logger log = LoggerFactory.getLogger(OAuthFlowService.class);

    /**
     * The code a stub-authorizing connector's consent URL hands straight back to the callback. It is
     * never presented to a provider — {@link #exchangeCodeForTokens} short-circuits before any POST —
     * and is spelled unmistakably so it can never be mistaken for a real grant in a log line.
     */
    private static final String STUB_AUTHORIZATION_CODE = "stub-authorization-code";

    /** Fallback scopes when a connector predates the {@link OAuth2Connector} scope declaration. */
    private static final List<String> DEFAULT_GOOGLE_SCOPES = List.of(
            "https://www.googleapis.com/auth/bigquery.readonly",
            "https://www.googleapis.com/auth/cloudplatformprojects.readonly");

    private final IntegrationOAuthStateRepository oAuthStateRepository;
    private final ConnectionService connectionService;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorAppCredentialService appCredentialService;
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
                            ConnectorAppCredentialService appCredentialService,
                            ObjectMapper objectMapper,
                            ConnectionHealthService connectionHealthService) {
        this.oAuthStateRepository = oAuthStateRepository;
        this.connectionService = connectionService;
        this.connectorRegistry = connectorRegistry;
        this.appCredentialService = appCredentialService;
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

    /**
     * The app credentials this project's flow runs as: its own stored pair if it has one, else the
     * deployment env vars. A project that has set nothing therefore resolves exactly what this method
     * resolved when it read {@code Environment} directly, down to the exception message — which names
     * the first missing property, as it always has.
     */
    private OAuthCredentials requireOAuthConfig(String projectId, OAuth2Connector connector) {
        var resolved = appCredentialService.resolve(projectId, connector);
        if (!resolved.configured()) {
            String missing = resolved.missingProperties().isEmpty()
                    ? connector.clientIdProperty()
                    : resolved.missingProperties().get(0);
            throw new IllegalStateException("OAuth client credentials not configured: " + missing);
        }
        return new OAuthCredentials(resolved.clientId(), resolved.clientSecret());
    }

    public String oauthCallbackUri() {
        return backendUrl + "/api/v1/oauth/callback";
    }

    @Transactional
    public String buildAuthorizationUrl(String projectId, String connectorId, String redirectUri) {
        OAuth2Connector connector = requireOAuth2Connector(connectorId);
        // A stub-authorizing connector has no provider to present credentials to, so it must not be
        // held to having any. Every real connector still resolves its app credentials first, and
        // still fails here — before a state row is written — when they are missing.
        boolean stubAuthorization = connector.usesStubAuthorization();
        OAuthCredentials creds = stubAuthorization ? null : requireOAuthConfig(projectId, connector);
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

        if (stubAuthorization) {
            // Straight back to our own callback, so the browser round trip completes without leaving
            // the machine — carrying the same state parameter a provider would echo, which is what
            // keeps the CSRF check and the project/connector round trip genuinely exercised.
            return UriComponentsBuilder.fromUriString(redirectUri)
                    .queryParam("code", STUB_AUTHORIZATION_CODE)
                    .queryParam("state", state)
                    .build().toUriString();
        }

        String scopes = String.join(connector.scopeDelimiter(), scopesFor(connectorId));
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(connector.authorizationUrl())
                .queryParam(connector.clientIdParamName(), creds.clientId())
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
        OAuth2Connector connector = requireOAuth2Connector(connectorId);

        Map<String, Object> tokenResponse = exchangeCodeForTokens(projectId, connectorId, code, redirectUri);

        String accessToken = (String) tokenResponse.get("access_token");
        String refreshToken = (String) tokenResponse.get("refresh_token");
        Object expiresIn = tokenResponse.get("expires_in");
        OffsetDateTime expiresAt = expiresIn != null
                ? OffsetDateTime.now().plusSeconds(((Number) expiresIn).longValue())
                : null;

        Connection conn = resolveConnection(projectId, connectorId, connector);

        if (connector.requiresAccountSelection()) {
            // The grant covers several accounts and a human still has to pick one. Persist the grant
            // so the picker can enumerate against it, and hand the browser back to the connector page
            // with the connection to finish — the completion hook runs on that selection instead.
            connectionService.storeTokens(conn, accessToken, refreshToken, expiresAt);
            oAuthStateRepository.delete(oauthState);
            log.info("OAuth callback awaiting account selection for connector={} project={} connection={}",
                    connectorId, projectId, conn.getId());
            return frontendUrl + "/app/projects/" + projectId + "/integrations/" + connectorId
                    + "?selectAccount=" + conn.getId();
        }

        OAuth2Connector.OAuthCompletion completion = connector.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest(accessToken, refreshToken, null));
        applyCompletion(conn, completion, accessToken, refreshToken, expiresAt);

        oAuthStateRepository.delete(oauthState);

        log.info("OAuth callback completed for connector={} project={}", connectorId, projectId);
        return frontendUrl + "/app/projects/" + projectId + "/integrations/" + connectorId;
    }

    /**
     * The connection this authorization belongs to. A single-instance connector reuses its one row —
     * exactly as before this seam existed. A connector that permits several connections gets a fresh
     * row per authorization, which is what keeps two accounts on the same platform distinct instead of
     * the second silently overwriting the first's tokens and config.
     */
    private Connection resolveConnection(String projectId, String connectorId, OAuth2Connector connector) {
        if (connector.getSpec().singleInstance()) {
            return connectionService.getOrCreateSingle(projectId, connectorId, AuthType.OAUTH2);
        }
        return connectionService.create(projectId, connectorId, AuthType.OAUTH2, connectorId, null);
    }

    /**
     * Persists what the completion hook produced. Credentials go through {@code storeTokens} (the
     * per-connection DEK envelope); the hook's config is plaintext JSON on the row and so carries
     * only non-secret identifiers. A hook that reports no token keeps the exchanged one, which is the
     * no-op default and therefore today's exact behaviour for every Google connector.
     */
    private void applyCompletion(Connection conn, OAuth2Connector.OAuthCompletion completion,
                                 String exchangedAccessToken, String exchangedRefreshToken,
                                 OffsetDateTime expiresAt) {
        String accessToken = completion.accessToken() != null ? completion.accessToken() : exchangedAccessToken;
        String refreshToken = completion.refreshToken() != null ? completion.refreshToken() : exchangedRefreshToken;
        connectionService.storeTokens(conn, accessToken, refreshToken, expiresAt);
        if (!completion.config().isEmpty()) {
            connectionService.updateConfig(conn, completion.config());
        }
        if (completion.label() != null && !completion.label().isBlank()) {
            connectionService.updateLabel(conn, completion.label());
        }
    }

    /**
     * Accounts the connection's stored grant covers, for the post-consent picker. Returns an empty
     * list for a connector that needs no selection, so a caller never has to know which is which.
     */
    public List<OAuth2Connector.OAuthAccount> listAuthorizableAccounts(Connection conn) {
        OAuth2Connector connector = requireOAuth2Connector(conn.getConnectorId());
        String accessToken = connectionService.decrypt(conn).accessToken();
        if (accessToken == null || accessToken.isBlank()) {
            throw new BusinessException("Connection " + conn.getId() + " has no stored OAuth token");
        }
        return connector.listAuthorizableAccounts(accessToken);
    }

    /**
     * Finalizes a connection that was parked awaiting an account choice: runs the completion hook
     * with the admin's selection, so the connector can mint the per-account credential and hand back
     * the non-secret identifiers that make the connection publishable.
     */
    @Transactional
    public Connection completeAccountSelection(Connection conn, String accountId) {
        OAuth2Connector connector = requireOAuth2Connector(conn.getConnectorId());
        var creds = connectionService.decrypt(conn);
        if (creds.accessToken() == null || creds.accessToken().isBlank()) {
            throw new BusinessException("Connection " + conn.getId() + " has no stored OAuth token");
        }
        OAuth2Connector.OAuthCompletion completion = connector.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest(creds.accessToken(), creds.refreshToken(), accountId));
        applyCompletion(conn, completion, creds.accessToken(), creds.refreshToken(), conn.getTokenExpiresAt());
        log.info("OAuth account selection completed for connection={} account={}", conn.getId(), accountId);
        return conn;
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
        // The connection's own project, so a refresh uses the same app the authorization ran as.
        OAuthCredentials creds = requireOAuthConfig(conn.getProjectId(), connector);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "refresh_token");
        params.add("refresh_token", refreshToken);
        params.add(connector.clientIdParamName(), creds.clientId());
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
    private Map<String, Object> exchangeCodeForTokens(String projectId, String connectorId, String code,
                                                      String redirectUri) {
        OAuth2Connector connector = requireOAuth2Connector(connectorId);
        if (connector.usesStubAuthorization()) {
            return stubTokenResponse(connectorId);
        }
        OAuthCredentials creds = requireOAuthConfig(projectId, connector);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("code", code);
        params.add("redirect_uri", redirectUri);
        params.add(connector.clientIdParamName(), creds.clientId());
        params.add("client_secret", creds.clientSecret());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
        ResponseEntity<Map> response = restTemplate.exchange(connector.tokenUrl(), HttpMethod.POST, request, Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("access_token")) {
            throw new com.conductor.exception.BusinessException("Token exchange failed: no access_token in response");
        }
        return body;
    }

    /**
     * The canned grant a stub-authorizing connector's exchange yields, shaped exactly as a provider's
     * would be so the rest of {@code handleCallback} cannot tell the difference. The tokens name
     * themselves as stubs: if one ever escaped to a real API the failure is legible rather than
     * mysterious.
     */
    private Map<String, Object> stubTokenResponse(String connectorId) {
        log.info("Stub OAuth token exchange for connector={} — no provider call made", connectorId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", "stub-access-token-" + connectorId);
        body.put("refresh_token", "stub-refresh-token-" + connectorId);
        body.put("expires_in", 3600);
        return body;
    }
}
