package com.conductor.agent.credential;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit test (no Spring/DB) for the local-profile provider-key envelope round-trip. */
class LocalProviderCredentialCryptoTest {

    private final LocalProviderCredentialCrypto crypto =
            new LocalProviderCredentialCrypto("test-key-32-bytes-padded-or-trunc");

    @Test
    void encryptsAndDecryptsRoundTrip() {
        ProviderCredential c = new ProviderCredential();
        crypto.putApiKey(c, "sk-ant-secret-123");

        assertThat(c.getKmsKeyReference()).isEqualTo("local");
        assertThat(c.getEncryptedApiKey()).isNotNull().isNotEqualTo("sk-ant-secret-123");
        assertThat(crypto.decryptApiKey(c)).isEqualTo("sk-ant-secret-123");
    }

    @Test
    void differentInvocationsUseFreshIvs() {
        ProviderCredential a = new ProviderCredential();
        ProviderCredential b = new ProviderCredential();
        crypto.putApiKey(a, "same-secret");
        crypto.putApiKey(b, "same-secret");

        // Random IV per encryption → ciphertexts differ even for identical plaintext.
        assertThat(a.getEncryptedApiKey()).isNotEqualTo(b.getEncryptedApiKey());
        assertThat(crypto.decryptApiKey(a)).isEqualTo("same-secret");
        assertThat(crypto.decryptApiKey(b)).isEqualTo("same-secret");
    }
}
