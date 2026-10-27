package com.conductor.scheduling;

import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.auth.oauth2.TokenVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerTokenVerifierTest {

    private static final String AUD = "https://conductor.example/internal/v1/scheduler";
    private static final String EMAIL = "scheduler@proj.iam.gserviceaccount.com";
    private static KeyPair keys;

    @BeforeAll
    static void generate() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
    }

    private static SchedulerTokenVerifier verifier(String aud, String email) {
        return new SchedulerTokenVerifier(aud, email, () -> TokenVerifier.newBuilder().setPublicKey(keys.getPublic()));
    }

    private static String token(String issuer, String audience, String email, Boolean emailVerified, long expiresIn)
            throws Exception {
        JsonWebSignature.Header header = new JsonWebSignature.Header().setAlgorithm("RS256");
        long now = Instant.now().getEpochSecond();
        JsonWebToken.Payload payload = new JsonWebToken.Payload()
                .setIssuer(issuer).setAudience(audience)
                .setIssuedAtTimeSeconds(now - 3600).setExpirationTimeSeconds(now + expiresIn);
        payload.set("email", email);
        if (emailVerified != null) {
            payload.set("email_verified", emailVerified);
        }
        return JsonWebSignature.signUsingRsaSha256(keys.getPrivate(), GsonFactory.getDefaultInstance(), header, payload);
    }

    @Test
    void validToken_returnsCallerEmail() throws Exception {
        assertThat(verifier(AUD, EMAIL).verify(token("https://accounts.google.com", AUD, EMAIL, true, 600)))
                .contains(EMAIL);
    }

    @Test
    void bareIssuerForm_isAccepted() throws Exception {
        assertThat(verifier(AUD, EMAIL).verify(token("accounts.google.com", AUD, EMAIL, true, 600))).contains(EMAIL);
    }

    @Test
    void wrongAudience_rejected() throws Exception {
        assertThat(verifier(AUD, EMAIL).verify(token("https://accounts.google.com", "other", EMAIL, true, 600)))
                .isEmpty();
    }

    @Test
    void wrongEmail_rejected() throws Exception {
        assertThat(verifier(AUD, EMAIL).verify(token("https://accounts.google.com", AUD, "evil@x.com", true, 600)))
                .isEmpty();
    }

    @Test
    void unverifiedOrMissingEmailVerified_rejected() throws Exception {
        assertThat(verifier(AUD, EMAIL).verify(token("https://accounts.google.com", AUD, EMAIL, false, 600))).isEmpty();
        assertThat(verifier(AUD, EMAIL).verify(token("https://accounts.google.com", AUD, EMAIL, null, 600))).isEmpty();
    }

    @Test
    void expired_rejected() throws Exception {
        assertThat(verifier(AUD, EMAIL).verify(token("https://accounts.google.com", AUD, EMAIL, true, -600)))
                .isEmpty();
    }

    @Test
    void wrongIssuer_rejected() throws Exception {
        assertThat(verifier(AUD, EMAIL).verify(token("https://evil.example", AUD, EMAIL, true, 600))).isEmpty();
    }

    @Test
    void wrongSignature_rejected() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair other = g.generateKeyPair();
        JsonWebSignature.Header header = new JsonWebSignature.Header().setAlgorithm("RS256");
        JsonWebToken.Payload payload = new JsonWebToken.Payload().setIssuer("https://accounts.google.com")
                .setAudience(AUD).setExpirationTimeSeconds(Instant.now().getEpochSecond() + 600);
        payload.set("email", EMAIL);
        payload.set("email_verified", true);
        String forged = JsonWebSignature.signUsingRsaSha256(other.getPrivate(), GsonFactory.getDefaultInstance(),
                header, payload);
        assertThat(verifier(AUD, EMAIL).verify(forged)).isEmpty();
    }

    @Test
    void garbageAndNullTokens_rejected() {
        SchedulerTokenVerifier v = verifier(AUD, EMAIL);
        assertThat(v.verify("not-a-jwt")).isEmpty();
        assertThat(v.verify(null)).isEmpty();
        assertThat(v.verify("  ")).isEmpty();
    }

    @Test
    void unsetConfig_rejectsEvenAValidToken() throws Exception {
        String t = token("https://accounts.google.com", AUD, EMAIL, true, 600);
        assertThat(verifier("", EMAIL).verify(t)).isEmpty();
        assertThat(verifier(AUD, "").verify(t)).isEmpty();
        assertThat(verifier(null, null).verify(t)).isEmpty();
    }

    @Test
    void certFetchFailure_isStillRejected() throws Exception {
        com.google.api.client.http.HttpTransport broken = new com.google.api.client.http.HttpTransport() {
            @Override
            protected com.google.api.client.http.LowLevelHttpRequest buildRequest(String method, String url)
                    throws java.io.IOException {
                throw new java.io.IOException("certs unreachable");
            }
        };
        SchedulerTokenVerifier v = new SchedulerTokenVerifier(AUD, EMAIL,
                () -> TokenVerifier.newBuilder().setHttpTransportFactory(() -> broken));
        assertThat(v.verify(token("https://accounts.google.com", AUD, EMAIL, true, 600))).isEmpty();
    }
}
