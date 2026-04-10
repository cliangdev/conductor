package com.conductor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService("test-secret-key-must-be-at-least-32-chars-long-enough", 24);
    }

    @Test
    void generatedTokenValidatesSuccessfully() {
        String token = jwtService.generateToken("user-123");

        assertThat(jwtService.validateToken(token)).isTrue();
    }

    @Test
    void generatedTokenContainsUserId() {
        String token = jwtService.generateToken("user-123");

        assertThat(jwtService.getUserIdFromToken(token)).isEqualTo("user-123");
    }

    @Test
    void expiredTokenIsInvalid() {
        JwtService shortLivedService = new JwtService(
                "test-secret-key-must-be-at-least-32-chars-long-enough", 0);

        String token = shortLivedService.generateToken("user-456");

        assertThat(shortLivedService.validateToken(token)).isFalse();
    }

    @Test
    void malformedTokenIsInvalid() {
        assertThat(jwtService.validateToken("not-a-jwt")).isFalse();
    }

    @Test
    void tokenSignedWithDifferentSecretIsInvalid() {
        JwtService otherService = new JwtService(
                "other-secret-key-must-be-at-least-32-chars-long-too", 24);

        String token = otherService.generateToken("user-789");

        assertThat(jwtService.validateToken(token)).isFalse();
    }
}
