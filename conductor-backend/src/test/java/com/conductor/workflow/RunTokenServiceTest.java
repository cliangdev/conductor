package com.conductor.workflow;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class RunTokenServiceTest {

    private static final String JWT_SECRET = "test-secret-key-must-be-at-least-32-chars-long-for-hmac";

    private RunTokenService runTokenService;

    @BeforeEach
    void setUp() {
        runTokenService = new RunTokenService(JWT_SECRET);
    }

    @Test
    void generateRunToken_containsRunIdAsSubject() {
        String token = runTokenService.generateRunToken("run-abc-123", 24);

        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo("run-abc-123");
    }

    @Test
    void generateRunToken_containsRunCallbackType() {
        String token = runTokenService.generateRunToken("run-abc-123", 24);

        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.get("type", String.class)).isEqualTo("run-callback");
    }

    @Test
    void generateRunToken_expiresAtNowPlusTtlHours() {
        String token = runTokenService.generateRunToken("run-xyz", 2);

        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Date iat = claims.getIssuedAt();
        Date exp = claims.getExpiration();
        long diffMs = exp.getTime() - iat.getTime();
        // Allow small rounding difference — JWT dates are second-precision
        assertThat(diffMs).isBetween(2 * 3600_000L - 1000, 2 * 3600_000L + 1000);
    }

    @Test
    void validateRunToken_returnsTrueForValidToken() {
        String token = runTokenService.generateRunToken("run-valid-1", 24);
        assertThat(runTokenService.validateRunToken(token, "run-valid-1")).isTrue();
    }

    @Test
    void validateRunToken_returnsFalseForWrongRunId() {
        String token = runTokenService.generateRunToken("run-a", 24);
        assertThat(runTokenService.validateRunToken(token, "run-b")).isFalse();
    }

    @Test
    void validateRunToken_returnsFalseForExpiredToken() {
        // Generate a token that expired 1 hour ago by using -1 TTL (will create expired token manually)
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        long nowMs = System.currentTimeMillis();
        String expiredToken = Jwts.builder()
                .subject("run-expired")
                .issuedAt(new Date(nowMs - 7200_000L))
                .expiration(new Date(nowMs - 3600_000L))
                .claim("type", "run-callback")
                .signWith(key)
                .compact();

        assertThat(runTokenService.validateRunToken(expiredToken, "run-expired")).isFalse();
    }

    @Test
    void validateRunToken_returnsFalseForTamperedToken() {
        String token = runTokenService.generateRunToken("run-tampered", 24);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThat(runTokenService.validateRunToken(tampered, "run-tampered")).isFalse();
    }
}
