package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.exception.CredentialEncryptionException;
import com.conductor.integration.DecryptedCredentials;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.kms.v1.CryptoKeyName;
import com.google.cloud.kms.v1.DecryptResponse;
import com.google.cloud.kms.v1.EncryptResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Envelope encryption with GCP KMS: a per-connection AES-256 DEK is generated, wrapped by the KMS
 * KEK, and stored Base64 in {@code kms_key_reference}; that same DEK encrypts every secret on the
 * connection (access token, refresh token, webhook secret) with AES/GCM.
 */
@Service
@Profile("!local")
public class GcpKmsCredentialService implements CredentialService {

    private static final Logger log = LoggerFactory.getLogger(GcpKmsCredentialService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final ObjectMapper objectMapper;
    private final KeyManagementServiceClient kmsClient;
    private final String kmsKeyName;

    public GcpKmsCredentialService(
            ObjectMapper objectMapper,
            KeyManagementServiceClient kmsClient,
            @Value("${GCP_PROJECT_ID:}") String gcpProjectId,
            @Value("${conductor.kms.location:global}") String kmsLocation,
            @Value("${conductor.kms.key-ring:conductor-secrets}") String kmsKeyRing,
            @Value("${conductor.kms.key-name:integration-credentials-kek}") String kmsKeyName) {
        this.objectMapper = objectMapper;
        this.kmsClient = kmsClient;
        this.kmsKeyName = CryptoKeyName.format(gcpProjectId, kmsLocation, kmsKeyRing, kmsKeyName);
    }

    @Override
    public void putTokens(Connection c, String accessToken, String refreshToken, OffsetDateTime expiresAt) {
        try {
            byte[] dek = ensureDek(c);
            c.setEncryptedAccessToken(encryptWithDek(dek, accessToken));
            c.setEncryptedRefreshToken(encryptWithDek(dek, refreshToken));
            c.setTokenExpiresAt(expiresAt);
        } catch (Exception e) {
            throw new CredentialEncryptionException("Failed to encrypt tokens", e);
        }
    }

    @Override
    public void putWebhookSecret(Connection c, String secret) {
        try {
            byte[] dek = ensureDek(c);
            c.setEncryptedWebhookSecret(encryptWithDek(dek, secret));
        } catch (Exception e) {
            throw new CredentialEncryptionException("Failed to encrypt webhook secret", e);
        }
    }

    @Override
    public DecryptedCredentials decryptTokens(Connection c) {
        try {
            byte[] dek = unwrapDek(c);
            String accessToken = dek != null ? decryptWithDek(dek, c.getEncryptedAccessToken()) : null;
            String refreshToken = dek != null ? decryptWithDek(dek, c.getEncryptedRefreshToken()) : null;
            return new DecryptedCredentials(
                    accessToken, refreshToken,
                    c.getTokenExpiresAt() != null ? c.getTokenExpiresAt().toInstant() : null,
                    parseConfigJson(c.getConfigJson()));
        } catch (Exception e) {
            throw new CredentialEncryptionException("Failed to decrypt credentials", e);
        }
    }

    @Override
    public String decryptWebhookSecret(Connection c) {
        try {
            byte[] dek = unwrapDek(c);
            return dek != null ? decryptWithDek(dek, c.getEncryptedWebhookSecret()) : null;
        } catch (Exception e) {
            throw new CredentialEncryptionException("Failed to decrypt webhook secret", e);
        }
    }

    // Reuse the connection's existing DEK, or generate + KMS-wrap a new one and stamp the reference.
    private byte[] ensureDek(Connection c) throws Exception {
        byte[] existing = unwrapDek(c);
        if (existing != null) {
            return existing;
        }
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        byte[] dek = keyGen.generateKey().getEncoded();
        EncryptResponse encrypted = kmsClient.encrypt(kmsKeyName, ByteString.copyFrom(dek));
        c.setKmsKeyReference(Base64.getEncoder().encodeToString(encrypted.getCiphertext().toByteArray()));
        return dek;
    }

    private byte[] unwrapDek(Connection c) {
        if (c.getKmsKeyReference() == null || c.getKmsKeyReference().isBlank()) {
            return null;
        }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfigJson(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
