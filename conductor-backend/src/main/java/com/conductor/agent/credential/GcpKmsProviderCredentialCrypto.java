package com.conductor.agent.credential;

import com.conductor.exception.CredentialEncryptionException;
import com.google.cloud.kms.v1.CryptoKeyName;
import com.google.cloud.kms.v1.DecryptResponse;
import com.google.cloud.kms.v1.EncryptResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Envelope encryption with GCP KMS for provider API keys: a per-row AES-256 DEK is generated,
 * wrapped by the KMS KEK and stored Base64 in {@code kms_key_reference}; that DEK encrypts the API
 * key with AES/GCM. Mirrors {@code GcpKmsCredentialService} but scoped to {@link ProviderCredential}
 * so the agent module shares no entity with the connector subsystem.
 */
@Service
@Profile("!local")
public class GcpKmsProviderCredentialCrypto implements ProviderCredentialCrypto {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final KeyManagementServiceClient kmsClient;
    private final String kmsKeyName;

    public GcpKmsProviderCredentialCrypto(
            KeyManagementServiceClient kmsClient,
            @Value("${GCP_PROJECT_ID:}") String gcpProjectId,
            @Value("${conductor.kms.location:global}") String kmsLocation,
            @Value("${conductor.kms.key-ring:conductor-secrets}") String kmsKeyRing,
            @Value("${conductor.kms.key-name:integration-credentials-kek}") String kmsKeyName) {
        this.kmsClient = kmsClient;
        this.kmsKeyName = CryptoKeyName.format(gcpProjectId, kmsLocation, kmsKeyRing, kmsKeyName);
    }

    @Override
    public void putApiKey(ProviderCredential c, String apiKey) {
        try {
            byte[] dek = ensureDek(c);
            c.setEncryptedApiKey(encryptWithDek(dek, apiKey));
        } catch (Exception e) {
            throw new CredentialEncryptionException("Failed to encrypt provider API key", e);
        }
    }

    @Override
    public String decryptApiKey(ProviderCredential c) {
        try {
            byte[] dek = unwrapDek(c);
            return dek != null ? decryptWithDek(dek, c.getEncryptedApiKey()) : null;
        } catch (Exception e) {
            throw new CredentialEncryptionException("Failed to decrypt provider API key", e);
        }
    }

    private byte[] ensureDek(ProviderCredential c) throws Exception {
        byte[] existing = unwrapDek(c);
        if (existing != null) return existing;
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        byte[] dek = keyGen.generateKey().getEncoded();
        EncryptResponse encrypted = kmsClient.encrypt(kmsKeyName, ByteString.copyFrom(dek));
        c.setKmsKeyReference(Base64.getEncoder().encodeToString(encrypted.getCiphertext().toByteArray()));
        return dek;
    }

    private byte[] unwrapDek(ProviderCredential c) {
        if (c.getKmsKeyReference() == null || c.getKmsKeyReference().isBlank()) return null;
        byte[] wrappedDek = Base64.getDecoder().decode(c.getKmsKeyReference());
        DecryptResponse decryptResponse = kmsClient.decrypt(kmsKeyName, ByteString.copyFrom(wrappedDek));
        return decryptResponse.getPlaintext().toByteArray();
    }

    private String encryptWithDek(byte[] dek, String plaintext) throws Exception {
        if (plaintext == null) return null;
        SecretKey key = new SecretKeySpec(dek, "AES");
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
        return Base64.getEncoder().encodeToString(combined);
    }

    private String decryptWithDek(byte[] dek, String base64Ciphertext) throws Exception {
        if (base64Ciphertext == null) return null;
        SecretKey key = new SecretKeySpec(dek, "AES");
        byte[] combined = Base64.getDecoder().decode(base64Ciphertext);
        byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
