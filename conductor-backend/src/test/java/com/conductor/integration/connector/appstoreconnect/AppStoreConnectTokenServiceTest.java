package com.conductor.integration.connector.appstoreconnect;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppStoreConnectTokenServiceTest {

    private KeyPair keyPair;
    private AppStoreConnectTokenService.Credentials creds;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        keyPair = gen.generateKeyPair();
        creds = new AppStoreConnectTokenService.Credentials("ISSUER-1", "KEY123", pem(keyPair));
    }

    static String pem(KeyPair pair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    @Test
    void tokenIsEs256SignedWithKidIssuerAudienceAndFifteenMinuteExpiry() {
        Instant now = Instant.parse("2026-10-16T10:00:00Z");
        AppStoreConnectTokenService service = new AppStoreConnectTokenService(Clock.fixed(now, ZoneOffset.UTC));

        String jwt = service.token("conn-1", creds);

        Jws<Claims> parsed = Jwts.parser()
                .verifyWith((ECPublicKey) keyPair.getPublic())
                .clock(() -> java.util.Date.from(now))
                .build().parseSignedClaims(jwt);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("ES256");
        assertThat(parsed.getHeader().getKeyId()).isEqualTo("KEY123");
        assertThat(parsed.getPayload().getIssuer()).isEqualTo("ISSUER-1");
        assertThat(parsed.getPayload().getAudience()).containsExactly("appstoreconnect-v1");
        assertThat(parsed.getPayload().getExpiration().toInstant()).isEqualTo(now.plusSeconds(15 * 60));
    }

    @Test
    void tokenIsReusedForTenMinutesPerConnectionThenRemintedAndEvictionForcesAFreshOne() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-16T10:00:00Z"));
        AppStoreConnectTokenService service = new AppStoreConnectTokenService(clock);

        String first = service.token("conn-1", creds);
        clock.advanceSeconds(9 * 60);
        assertThat(service.token("conn-1", creds)).isEqualTo(first);
        clock.advanceSeconds(2 * 60);
        String second = service.token("conn-1", creds);
        assertThat(second).isNotEqualTo(first);

        service.evict("conn-1");
        clock.advanceSeconds(1);
        assertThat(service.token("conn-1", creds)).isNotEqualTo(second);
    }

    @Test
    void aGarbageKeyIsRejectedWithAClearMessage() {
        AppStoreConnectTokenService service = new AppStoreConnectTokenService();
        assertThatThrownBy(() -> service.token("c", new AppStoreConnectTokenService.Credentials("i", "k", "nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".p8");
    }

    @Test
    void credentialsAreCompleteOnlyWithAllThreeParts() {
        assertThat(creds.complete()).isTrue();
        assertThat(new AppStoreConnectTokenService.Credentials("i", " ", "pem").complete()).isFalse();
        assertThat(new AppStoreConnectTokenService.Credentials("i", "k", null).complete()).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advanceSeconds(long s) { now = now.plusSeconds(s); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
