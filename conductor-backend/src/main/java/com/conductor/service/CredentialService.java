package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.EnvelopeEncrypted;
import com.conductor.integration.DecryptedCredentials;

import java.time.OffsetDateTime;

/**
 * The Integrations secret envelope. One per-row DEK (wrapped in {@code kmsKeyReference}) encrypts
 * every secret on that row. These methods mutate the entity in memory; persistence is the caller's
 * responsibility (see {@code ConnectionService}).
 *
 * <p>The {@link Connection} methods are the convenience surface for the connection row and its three
 * known secrets. {@link #encryptSecret} / {@link #decryptSecret} are the same envelope stated
 * generically, over any {@link EnvelopeEncrypted} row — that is what lets a second table
 * ({@code connector_app_credential}) reuse this implementation instead of growing a second copy of
 * the AES/GCM and KEK-wrapping code.
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

    /**
     * Encrypt one secret under {@code owner}'s DEK, generating and wrapping that DEK on first use,
     * and return the ciphertext for the caller to store on the row. Null plaintext yields null.
     */
    String encryptSecret(EnvelopeEncrypted owner, String plaintext);

    /**
     * Decrypt a ciphertext produced by {@link #encryptSecret} for the same {@code owner} row. Returns
     * null when {@code ciphertext} is null, or when the row carries no DEK to open it with.
     */
    String decryptSecret(EnvelopeEncrypted owner, String ciphertext);
}
