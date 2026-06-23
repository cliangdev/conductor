package com.conductor.integration.connector.applesearchads;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppleAdsTokenServiceTest {

    @Mock private RestTemplate restTemplate;

    private KeyPair keyPair;
    private AppleAdsTokenService service;
    private AppleAdsTokenService.Credentials creds;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256); // P-256, matches ES256
        keyPair = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        service = new AppleAdsTokenService(restTemplate);
        creds = new AppleAdsTokenService.Credentials("CLIENT.abc", "TEAM.xyz", "KEY123", pem);
    }

    @Test
    void clientSecret_isEs256SignedWithExpectedClaimsAndKidHeader() {
        String jwt = service.buildClientSecret(creds);

        Jws<Claims> parsed = Jwts.parser()
                .verifyWith((ECPublicKey) keyPair.getPublic())
                .build()
                .parseSignedClaims(jwt);

        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("ES256");
        assertThat(parsed.getHeader().getKeyId()).isEqualTo("KEY123");
        assertThat(parsed.getPayload().getIssuer()).isEqualTo("TEAM.xyz");
        assertThat(parsed.getPayload().getSubject()).isEqualTo("CLIENT.abc");
        assertThat(parsed.getPayload().getAudience()).contains("https://appleid.apple.com");
        assertThat(parsed.getPayload().getExpiration()).isNotNull();
        assertThat(parsed.getPayload().getIssuedAt()).isNotNull();
    }

    @Test
    void accessToken_isCachedAndNotRefetchedWhileFresh() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "tok-1", "expires_in", 3600)));

        String first = service.accessToken("conn-1", creds);
        String second = service.accessToken("conn-1", creds);

        assertThat(first).isEqualTo("tok-1");
        assertThat(second).isEqualTo("tok-1");
        verify(restTemplate, times(1))
                .exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    void evict_forcesAfreshExchange() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "tok-1", "expires_in", 3600)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "tok-2", "expires_in", 3600)));

        String first = service.accessToken("conn-1", creds);
        service.evict("conn-1");
        String second = service.accessToken("conn-1", creds);

        assertThat(first).isEqualTo("tok-1");
        assertThat(second).isEqualTo("tok-2");
        verify(restTemplate, times(2))
                .exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }
}
