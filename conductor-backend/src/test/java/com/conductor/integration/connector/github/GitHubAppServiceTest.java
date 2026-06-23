package com.conductor.integration.connector.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubAppServiceTest {

    @Mock private RestTemplate restTemplate;

    private KeyPair keyPair;
    private GitHubAppService service;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        service = new GitHubAppService(restTemplate, new ObjectMapper(), "12345", "conductor", pem);
    }

    @Test
    void appJwt_isSignedWithThePrivateKey_andHasExpectedClaims() {
        String jwt = service.appJwt();
        Jws<Claims> parsed = Jwts.parser()
                .verifyWith((RSAPublicKey) keyPair.getPublic())
                .build()
                .parseSignedClaims(jwt);
        assertThat(parsed.getPayload().getIssuer()).isEqualTo("12345");
        assertThat(parsed.getPayload().getExpiration().toInstant()).isAfter(Instant.now());
    }

    @Test
    void isConfigured_trueWhenAllPresent() {
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void installationToken_isCachedAndNotRefetchedWhileFresh() throws Exception {
        String body = "{\"token\":\"ghs_abc\",\"expires_at\":\""
                + Instant.now().plusSeconds(3600) + "\"}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));

        assertThat(service.installationToken("42")).isEqualTo("ghs_abc");
        assertThat(service.installationToken("42")).isEqualTo("ghs_abc"); // served from cache

        verify(restTemplate, times(1))
                .exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void evictInstallationToken_removesCachedEntry_forcingARefetch() throws Exception {
        String body = "{\"token\":\"ghs_abc\",\"expires_at\":\""
                + Instant.now().plusSeconds(3600) + "\"}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));

        assertThat(service.installationToken("42")).isEqualTo("ghs_abc"); // populates cache (1 call)
        service.evictInstallationToken("42");                             // drops the entry
        assertThat(service.installationToken("42")).isEqualTo("ghs_abc"); // must refetch (2nd call)

        verify(restTemplate, times(2))
                .exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void evictInstallationToken_nullId_isNoOp() {
        service.evictInstallationToken(null); // does not throw
    }

    @Test
    void parsePrivateKey_rejectsGarbageWithHelpfulMessage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> GitHubAppService.parsePrivateKey("not-a-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }
}
