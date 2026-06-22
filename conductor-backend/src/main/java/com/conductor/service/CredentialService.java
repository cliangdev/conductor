package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.integration.DecryptedCredentials;

import java.time.OffsetDateTime;

/**
 * Pure crypto over a {@link Connection} entity. One per-connection DEK (wrapped in
 * {@code kmsKeyReference}) encrypts all secrets on the row. These methods mutate the entity in
 * memory; persistence is the caller's responsibility (see {@code ConnectionService}).
 */
public interface CredentialService {

    /** Encrypt access/refresh tokens into the connection (generates the DEK on first use). */
    void putTokens(Connection c, String accessToken, String refreshToken, OffsetDateTime expiresAt);

    /** Encrypt the webhook signing secret into the connection (reuses the same DEK). */
    void putWebhookSecret(Connection c, String secret);

    /** Decrypt access/refresh tokens + parse config_json. Returns null tokens if none stored. */
    DecryptedCredentials decryptTokens(Connection c);

    /** Decrypt the webhook signing secret, or null if none stored. */
    String decryptWebhookSecret(Connection c);
}
