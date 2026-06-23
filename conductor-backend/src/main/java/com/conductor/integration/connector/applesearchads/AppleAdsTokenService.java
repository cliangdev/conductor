package com.conductor.integration.connector.applesearchads;

import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apple Search Ads authentication, isolated to the applesearchads connector.
 *
 * <p>Apple's API uses a two-step client-credentials flow:
 * <ol>
 *   <li>Mint a JWT "client secret" signed with the team's EC private key (the .p8), ES256, valid for
 *       up to 180 days (we use ~150).</li>
 *   <li>Exchange that JWT at {@code appleid.apple.com/auth/oauth2/token} for a 1h bearer access token.</li>
 * </ol>
 * Access tokens are cached in-memory per connection id and refreshed shortly before expiry. All of this
 * is self-contained: the connector instantiates this service directly and there is no shared Spring bean,
 * no {@code AuthType} value, and no {@code OAuthFlowService} involvement.
 */
public class AppleAdsTokenService {

    private static final Logger log = LoggerFactory.getLogger(AppleAdsTokenService.class);

    private static final String TOKEN_URL = "https://appleid.apple.com/auth/oauth2/token";
    private static final String AUDIENCE = "https://appleid.apple.com";
    private static final String SCOPE = "searchadsorg";
    /** Apple caps the client-secret JWT at 180 days; stay comfortably under it. */
    private static final Duration CLIENT_SECRET_TTL = Duration.ofDays(150);
    /** Refresh the access token once it is within this window of expiring. */
    private static final long REFRESH_SKEW_SECONDS = 60;

    private final RestTemplate restTemplate;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public AppleAdsTokenService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** Inputs needed to mint a token, pulled out of the connection context by the connector. */
    public record Credentials(String clientId, String teamId, String keyId, String privateKeyPem) {
        public boolean complete() {
            return notBlank(clientId) && notBlank(teamId) && notBlank(keyId) && notBlank(privateKeyPem);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    /**
     * A cached (or freshly minted) access token for one connection. Reuses a non-expired token; otherwise
     * mints a new client-secret JWT and exchanges it.
     */
    public String accessToken(String connectionId, Credentials creds) {
        CachedToken cached = tokenCache.get(connectionId);
        if (cached != null && Instant.now().isBefore(cached.expiresAt().minusSeconds(REFRESH_SKEW_SECONDS))) {
            return cached.token();
        }
        String clientSecret = buildClientSecret(creds);
        TokenResponse resp = exchange(creds.clientId(), clientSecret);
        Instant expiry = Instant.now().plusSeconds(resp.expiresIn() > 0 ? resp.expiresIn() : 3600);
        purgeExpired();
        tokenCache.put(connectionId, new CachedToken(resp.accessToken(), expiry));
        return resp.accessToken();
    }

    /**
     * Drop a connection's cached token. Mirrors {@code GitHubAppService.evictInstallationToken} —
     * the cache-hygiene hook to call when a connection is disconnected. (There is no generic
     * connection-disconnect SPI hook yet, so this is currently exercised only by tests; see the
     * connector docs for that follow-up.)
     */
    public void evict(String connectionId) {
        if (connectionId != null) {
            tokenCache.remove(connectionId);
        }
    }

    /**
     * Build the ES256-signed client-secret JWT: header {@code kid=keyId}; claims {@code iss=teamId},
     * {@code sub=clientId}, {@code aud=https://appleid.apple.com}, {@code iat=now}, {@code exp=now+150d}.
     */
    String buildClientSecret(Credentials creds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(creds.keyId()).and()
                .issuer(creds.teamId())
                .subject(creds.clientId())
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(CLIENT_SECRET_TTL)))
                .signWith(parsePrivateKey(creds.privateKeyPem()), Jwts.SIG.ES256)
                .compact();
    }

    /** Parse a PKCS#8 EC private key out of a .p8 PEM (strip the armor + whitespace, Base64-decode). */
    static PrivateKey parsePrivateKey(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n");
            String body = normalized
                    .replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            KeyFactory kf = KeyFactory.getInstance("EC");
            return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse the Apple Search Ads .p8 private key — must be a PKCS#8 EC PEM "
                            + "('BEGIN PRIVATE KEY')", e);
        }
    }

    @SuppressWarnings("unchecked")
    private TokenResponse exchange(String clientId, String clientSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("scope", SCOPE);

        ResponseEntity<Map> resp = restTemplate.exchange(
                TOKEN_URL, HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        Map<String, Object> body = resp.getBody();
        if (body == null || body.get("access_token") == null) {
            throw new IllegalStateException("Apple token endpoint returned no access_token");
        }
        String token = body.get("access_token").toString();
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 3600;
        return new TokenResponse(token, expiresIn);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tokenCache.values().removeIf(c -> !now.isBefore(c.expiresAt()));
    }

    private record TokenResponse(String accessToken, long expiresIn) {}

    private record CachedToken(String token, Instant expiresAt) {}
}
