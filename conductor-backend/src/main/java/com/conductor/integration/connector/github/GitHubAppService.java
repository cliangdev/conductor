package com.conductor.integration.connector.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GitHub App authentication + API helper, isolated to the github connector. Mints an app JWT (RS256,
 * signed with the app private key), exchanges it for short-lived per-installation tokens (cached), and
 * calls the GitHub REST API to validate installations and list their repositories. App-level secrets
 * (id/slug/private key) are env-injected — never stored per connection.
 */
@Service
public class GitHubAppService {

    private static final Logger log = LoggerFactory.getLogger(GitHubAppService.class);
    private static final String API_BASE = "https://api.github.com";
    private static final String API_VERSION = "2022-11-28";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String appId;
    private final String appSlug;
    private final String privateKeyPem;

    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public GitHubAppService(RestTemplate restTemplate,
                            ObjectMapper objectMapper,
                            @Value("${GITHUB_APP_ID:}") String appId,
                            @Value("${GITHUB_APP_SLUG:}") String appSlug,
                            @Value("${GITHUB_APP_PRIVATE_KEY:}") String privateKeyPem) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.appId = appId;
        this.appSlug = appSlug;
        this.privateKeyPem = privateKeyPem;
    }

    public boolean isConfigured() {
        return appId != null && !appId.isBlank()
                && appSlug != null && !appSlug.isBlank()
                && privateKeyPem != null && !privateKeyPem.isBlank();
    }

    /** The GitHub install URL; GitHub renders the native account + repo picker. */
    public String buildInstallUrl(String state) {
        return "https://github.com/apps/" + appSlug + "/installations/new?state=" + state;
    }

    /** Validate an installation id and read its account/selection (the Setup-URL id is spoofable). */
    public InstallationInfo getInstallation(String installationId) {
        JsonNode root = exchangeJson(HttpMethod.GET,
                API_BASE + "/app/installations/" + installationId, appJwtAuth(), null);
        String account = root.path("account").path("login").asText(null);
        String htmlUrl = root.path("html_url").asText(null);
        String repositorySelection = root.path("repository_selection").asText(null);
        return new InstallationInfo(account, htmlUrl, repositorySelection);
    }

    /** Repos accessible to the installation (paginated), for the UI list. */
    public List<Repo> listInstallationRepositories(String installationId) {
        String token = installationToken(installationId);
        List<Repo> repos = new ArrayList<>();
        int page = 1;
        while (true) {
            JsonNode root = exchangeJson(HttpMethod.GET,
                    API_BASE + "/installation/repositories?per_page=100&page=" + page,
                    bearer(token), null);
            JsonNode items = root.path("repositories");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode r : items) {
                repos.add(new Repo(r.path("full_name").asText(null), r.path("private").asBoolean(false)));
            }
            if (items.size() < 100) {
                break;
            }
            page++;
        }
        return repos;
    }

    /** A cached installation access token (~1h GitHub lifetime; refreshed shortly before expiry). */
    public String installationToken(String installationId) {
        CachedToken cached = tokenCache.get(installationId);
        if (cached != null && Instant.now().isBefore(cached.expiresAt().minusSeconds(300))) {
            return cached.token();
        }
        JsonNode root = exchangeJson(HttpMethod.POST,
                API_BASE + "/app/installations/" + installationId + "/access_tokens", appJwtAuth(), null);
        String token = root.path("token").asText(null);
        String expiresAt = root.path("expires_at").asText(null);
        Instant expiry = expiresAt != null ? Instant.parse(expiresAt) : Instant.now().plusSeconds(3300);
        tokenCache.put(installationId, new CachedToken(token, expiry));
        return token;
    }

    /** Build an app JWT (RS256): iss=App ID, iat=now-60s (clock skew), exp=now+9min. */
    String appJwt() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(appId)
                .issuedAt(Date.from(now.minusSeconds(60)))
                .expiration(Date.from(now.plusSeconds(540)))
                .signWith(parsePrivateKey(privateKeyPem), Jwts.SIG.RS256)
                .compact();
    }

    static PrivateKey parsePrivateKey(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n");
            String body = normalized
                    .replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse GITHUB_APP_PRIVATE_KEY — must be a PKCS#8 PEM ('BEGIN PRIVATE KEY'); "
                            + "convert with: openssl pkcs8 -topk8 -nocrypt", e);
        }
    }

    private HttpHeaders appJwtAuth() {
        return bearer(appJwt());
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
        headers.set("X-GitHub-Api-Version", API_VERSION);
        return headers;
    }

    private JsonNode exchangeJson(HttpMethod method, String url, HttpHeaders headers, Object body) {
        try {
            // Read as String (StringHttpMessageConverter is always present and content-type agnostic),
            // then parse with our ObjectMapper — avoids depending on a JsonNode message converter.
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, method, new HttpEntity<>(body, headers), String.class);
            String payload = resp.getBody();
            return (payload == null || payload.isBlank())
                    ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                    : objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("GitHub API call failed: {} {} — {}", method, url, e.getMessage());
            throw new RuntimeException("GitHub API call failed: " + e.getMessage(), e);
        }
    }

    public record InstallationInfo(String accountLogin, String htmlUrl, String repositorySelection) {}

    public record Repo(String fullName, boolean isPrivate) {}

    private record CachedToken(String token, Instant expiresAt) {}
}
