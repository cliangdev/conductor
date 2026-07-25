package com.conductor.integration.connector.github;

import com.conductor.exception.BusinessException;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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

    // --- scoped (repo-narrowed) vs unscoped installation tokens (#Phase B credential injection) ---

    @Test
    void installationTokenScoped_andUnscoped_cacheIndependently() throws Exception {
        String unscopedBody = "{\"token\":\"ghs_unscoped\",\"expires_at\":\"" + Instant.now().plusSeconds(3600) + "\"}";
        String scopedBody = "{\"token\":\"ghs_scoped\",\"expires_at\":\"" + Instant.now().plusSeconds(3600) + "\"}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST),
                argThat(entity -> entity.getBody() == null), eq(String.class)))
                .thenReturn(ResponseEntity.ok(unscopedBody));
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST),
                argThat(entity -> entity.getBody() != null), eq(String.class)))
                .thenReturn(ResponseEntity.ok(scopedBody));

        // Unscoped call.
        assertThat(service.installationToken("42")).isEqualTo("ghs_unscoped");
        // Scoped call for a specific repo — must not reuse the unscoped cache entry.
        GitHubAppService.InstallationTokenResult scoped =
                service.installationToken("42", java.util.List.of("Rexworks-LLC/nexus-backend"));
        assertThat(scoped.token()).isEqualTo("ghs_scoped");
        // A later unscoped call must still be served from its own cache entry, not the scoped one.
        assertThat(service.installationToken("42")).isEqualTo("ghs_unscoped");

        verify(restTemplate, times(1)).exchange(any(String.class), eq(HttpMethod.POST),
                argThat(entity -> entity.getBody() == null), eq(String.class));
        verify(restTemplate, times(1)).exchange(any(String.class), eq(HttpMethod.POST),
                argThat(entity -> entity.getBody() != null), eq(String.class));
    }

    @Test
    void installationTokenScoped_sortsRepositoryListForCacheKey() throws Exception {
        String body = "{\"token\":\"ghs_scoped\",\"expires_at\":\"" + Instant.now().plusSeconds(3600) + "\"}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));

        GitHubAppService.InstallationTokenResult first =
                service.installationToken("42", java.util.List.of("repo-a", "repo-b"));
        GitHubAppService.InstallationTokenResult second =
                service.installationToken("42", java.util.List.of("repo-b", "repo-a")); // reversed order

        assertThat(first.token()).isEqualTo("ghs_scoped");
        assertThat(second.token()).isEqualTo("ghs_scoped");
        // Same cache entry regardless of list order — only one exchange call.
        verify(restTemplate, times(1))
                .exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void evictInstallationToken_clearsUnscopedAndScopedEntries() throws Exception {
        String unscopedBody = "{\"token\":\"ghs_unscoped\",\"expires_at\":\"" + Instant.now().plusSeconds(3600) + "\"}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(unscopedBody));

        service.installationToken("42"); // unscoped — 1st call
        service.installationToken("42", java.util.List.of("Rexworks-LLC/nexus-backend")); // scoped — 2nd call
        service.installationToken("99"); // a different installation — 3rd call, must be unaffected by eviction below

        service.evictInstallationToken("42");

        service.installationToken("42"); // must refetch (evicted) — 4th call
        service.installationToken("42", java.util.List.of("Rexworks-LLC/nexus-backend")); // must refetch (evicted) — 5th call
        service.installationToken("99"); // still cached — no extra call

        verify(restTemplate, times(5))
                .exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    // --- validatePersonalAccessToken() : PAT bind-time validation against GitHub's GET /user ---

    @Test
    void validatePersonalAccessToken_parsesGitHubExpirationHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("github-authentication-token-expiration", "2023-04-27 00:38:52 UTC");
        when(restTemplate.exchange(eq("https://api.github.com/user"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"login\":\"octocat\"}", headers, HttpStatus.OK));

        GitHubAppService.PatValidationResult result = service.validatePersonalAccessToken("ghp_fine_grained");

        assertThat(result.login()).isEqualTo("octocat");
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2023-04-27T00:38:52Z"));
    }

    @Test
    void validatePersonalAccessToken_noExpirationHeader_returnsNullExpiry() {
        // Non-expiring classic PATs — GitHub simply omits the header.
        when(restTemplate.exchange(eq("https://api.github.com/user"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"login\":\"octocat\"}"));

        GitHubAppService.PatValidationResult result = service.validatePersonalAccessToken("ghp_classic");

        assertThat(result.login()).isEqualTo("octocat");
        assertThat(result.expiresAt()).isNull();
    }

    @Test
    void validatePersonalAccessToken_unauthorized_throwsActionableBusinessException() {
        when(restTemplate.exchange(eq("https://api.github.com/user"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                        HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> service.validatePersonalAccessToken("bad-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GitHub rejected this token");
    }

    @Test
    void validatePersonalAccessToken_forbidden_throwsActionableBusinessException() {
        when(restTemplate.exchange(eq("https://api.github.com/user"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                        HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> service.validatePersonalAccessToken("revoked-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GitHub rejected this token");
    }

    @Test
    void parsePrivateKey_rejectsGarbageWithHelpfulMessage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> GitHubAppService.parsePrivateKey("not-a-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }
}
