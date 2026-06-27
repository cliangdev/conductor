package com.conductor.agent.credential;

import com.conductor.exception.CredentialEncryptionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Local-profile crypto for provider API keys: a single static AES-256 key encrypts the key;
 * {@code kms_key_reference} is set to {@code "local"}. Mirrors {@code LocalCredentialService} so
 * agent tests run on the {@code local} profile with no KMS, reusing the same dev key property.
 */
@Service
@Profile("local")
public class LocalProviderCredentialCrypto implements ProviderCredentialCrypto {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey secretKey;

    public LocalProviderCredentialCrypto(
            @Value("${conductor.integration.local-encryption-key:dev-only-32-byte-key-not-4-prod}") String localKey) {
        byte[] keyBytes = Arrays.copyOf(localKey.getBytes(StandardCharsets.UTF_8), 32);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public void putApiKey(ProviderCredential c, String apiKey) {
        try {
            c.setKmsKeyReference("local");
            c.setEncryptedApiKey(encrypt(apiKey));
        } catch (Exception e) {
            throw new CredentialEncryptionException("Failed to encrypt provider API key", e);
        }
    }

    @Override
    public String decryptApiKey(ProviderCredential c) {
        try {
            return decrypt(c.getEncryptedApiKey());
        } catch (Exception e) {
            throw new CredentialEncryptionException("Failed to decrypt provider API key", e);
        }
    }

    private String encrypt(String plaintext) throws Exception {
        if (plaintext == null) return null;
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
        return Base64.getEncoder().encodeToString(combined);
    }

    private String decrypt(String base64Ciphertext) throws Exception {
        if (base64Ciphertext == null) return null;
        byte[] combined = Base64.getDecoder().decode(base64Ciphertext);
        byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
