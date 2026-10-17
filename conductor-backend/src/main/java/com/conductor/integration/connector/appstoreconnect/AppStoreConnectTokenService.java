package com.conductor.integration.connector.appstoreconnect;

import com.conductor.integration.connector.apple.ApplePrivateKeys;
import io.jsonwebtoken.Jwts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * App Store Connect API authentication: unlike Apple Search Ads there is no token exchange. The ES256 JWT
 * (header {@code kid=keyId}; claims {@code iss=issuerId}, {@code aud=appstoreconnect-v1}, {@code exp} within
 * 20 minutes) is itself the bearer. Tokens are minted for 15 minutes and reused for 10, per connection.
 */
public class AppStoreConnectTokenService {

    static final String AUDIENCE = "appstoreconnect-v1";
    static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    static final Duration REUSE_FOR = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public AppStoreConnectTokenService() {
        this(Clock.systemUTC());
    }

    AppStoreConnectTokenService(Clock clock) {
        this.clock = clock;
    }

    /** Inputs needed to mint a token, pulled out of the connection context by the connector. */
    public record Credentials(String issuerId, String keyId, String privateKeyPem) {
        public boolean complete() {
            return notBlank(issuerId) && notBlank(keyId) && notBlank(privateKeyPem);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    /** A cached (or freshly minted) bearer token for one connection. */
    public String token(String connectionId, Credentials creds) {
        Instant now = clock.instant();
        Cached cached = cache.get(connectionId);
        if (cached != null && now.isBefore(cached.mintedAt().plus(REUSE_FOR))) {
            return cached.token();
        }
        String token = buildToken(creds, now);
        cache.values().removeIf(c -> !now.isBefore(c.mintedAt().plus(REUSE_FOR)));
        cache.put(connectionId, new Cached(token, now));
        return token;
    }

    public void evict(String connectionId) {
        if (connectionId != null) {
            cache.remove(connectionId);
        }
    }

    String buildToken(Credentials creds, Instant now) {
        return Jwts.builder()
                .header().keyId(creds.keyId()).and()
                .issuer(creds.issuerId())
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(ApplePrivateKeys.parse(creds.privateKeyPem()), Jwts.SIG.ES256)
                .compact();
    }

    private record Cached(String token, Instant mintedAt) {}
}
