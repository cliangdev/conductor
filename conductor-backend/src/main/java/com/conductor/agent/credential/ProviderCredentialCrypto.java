package com.conductor.agent.credential;

/**
 * Pure crypto over a {@link ProviderCredential}: encrypts/decrypts the BYO API key in place. One
 * per-row DEK (wrapped in {@code kmsKeyReference}) encrypts the key. Two profile-scoped impls mirror
 * the connector {@code CredentialService} split: GCP KMS ({@code !local}) and a static key
 * ({@code local}). Persistence is the caller's responsibility (see {@link ProviderCredentialService}).
 */
public interface ProviderCredentialCrypto {

    /** Encrypt the API key into the credential (generates the DEK on first use). */
    void putApiKey(ProviderCredential credential, String apiKey);

    /** Decrypt the stored API key, or null if none stored. */
    String decryptApiKey(ProviderCredential credential);
}
