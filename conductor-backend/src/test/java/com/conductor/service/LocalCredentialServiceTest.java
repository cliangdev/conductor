package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.integration.DecryptedCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCredentialServiceTest {

    private LocalCredentialService service;

    private static final String ACCESS_TOKEN = "super-secret-access-token";
    private static final String REFRESH_TOKEN = "super-secret-refresh-token";
    private static final String WEBHOOK_SECRET = "super-secret-webhook-secret";

    @BeforeEach
    void setUp() {
        service = new LocalCredentialService(new ObjectMapper(), "test-encryption-key-for-unit-tests");
    }

    private Connection newConnection() {
        Connection c = new Connection();
        c.setProjectId("project-1");
        c.setConnectorId("posthog");
        c.setConfigJson("{\"foo\":\"bar\"}");
        return c;
    }

    @Test
    void putThenDecrypt_roundTripsTokensAndConfig() {
        Connection c = newConnection();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(1);

        service.putTokens(c, ACCESS_TOKEN, REFRESH_TOKEN, expiresAt);
        DecryptedCredentials result = service.decryptTokens(c);

        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(result.refreshToken()).isEqualTo(REFRESH_TOKEN);
        assertThat(result.expiresAt()).isEqualTo(expiresAt.toInstant());
        assertThat(result.configJson()).containsEntry("foo", "bar");
    }

    @Test
    void webhookSecret_roundTripsAndSharesTheDek() {
        Connection c = newConnection();
        service.putTokens(c, ACCESS_TOKEN, null, null);
        service.putWebhookSecret(c, WEBHOOK_SECRET);

        assertThat(service.decryptWebhookSecret(c)).isEqualTo(WEBHOOK_SECRET);
        // Tokens still decrypt after the webhook secret was added (same DEK).
        assertThat(service.decryptTokens(c).accessToken()).isEqualTo(ACCESS_TOKEN);
    }

    @Test
    void storedValues_doNotContainPlaintext() {
        Connection c = newConnection();
        service.putTokens(c, ACCESS_TOKEN, REFRESH_TOKEN, OffsetDateTime.now().plusHours(1));
        service.putWebhookSecret(c, WEBHOOK_SECRET);

        assertThat(c.getEncryptedAccessToken()).isNotNull().doesNotContain(ACCESS_TOKEN);
        assertThat(c.getEncryptedRefreshToken()).isNotNull().doesNotContain(REFRESH_TOKEN);
        assertThat(c.getEncryptedWebhookSecret()).isNotNull().doesNotContain(WEBHOOK_SECRET);
    }
}
