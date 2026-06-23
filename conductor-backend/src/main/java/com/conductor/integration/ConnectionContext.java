package com.conductor.integration;

import java.time.Instant;
import java.util.Map;

/**
 * Everything a connector needs to act on ONE connection instance. Replaces the bare
 * {@code DecryptedCredentials} — carrying the instance identity + decrypted secrets — which is what
 * makes every connector multi-instance-capable: the framework loops connections and hands each
 * connector one context at a time.
 */
public record ConnectionContext(String projectId, String connectorId, String connectionId,
                                String accessToken, String refreshToken, Instant expiresAt,
                                Map<String, Object> config, String webhookSecret) {

    public static ConnectionContext of(String projectId, String connectorId, String connectionId,
                                       DecryptedCredentials creds, String webhookSecret) {
        return new ConnectionContext(
                projectId, connectorId, connectionId,
                creds != null ? creds.accessToken() : null,
                creds != null ? creds.refreshToken() : null,
                creds != null ? creds.expiresAt() : null,
                creds != null && creds.configJson() != null ? creds.configJson() : Map.of(),
                webhookSecret);
    }

    public Object configValue(String key) {
        return config != null ? config.get(key) : null;
    }
}
