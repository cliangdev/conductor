package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.integration.DecryptedCredentials;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.OffsetDateTime;
import java.util.*;

/** Local-profile crypto: a single static AES-256 key encrypts all secrets; kms_key_reference = "local". */
@Service
@Profile("local")
public class LocalCredentialService implements CredentialService {

    private static final Logger log = LoggerFactory.getLogger(LocalCredentialService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final ObjectMapper objectMapper;
    private final SecretKey secretKey;

    public LocalCredentialService(
            ObjectMapper objectMapper,
            @Value("${conductor.integration.local-encryption-key:dev-only-32-byte-key-not-4-prod}") String localKey) {
        this.objectMapper = objectMapper;
        // Pad or truncate to exactly 32 bytes for AES-256
        byte[] keyBytes = Arrays.copyOf(localKey.getBytes(StandardCharsets.UTF_8), 32);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public void putTokens(Connection c, String accessToken, String refreshToken, OffsetDateTime expiresAt) {
        c.setKmsKeyReference("local");
        c.setEncryptedAccessToken(encrypt(accessToken));
        c.setEncryptedRefreshToken(encrypt(refreshToken));
        c.setTokenExpiresAt(expiresAt);
    }

    @Override
    public void putWebhookSecret(Connection c, String secret) {
        c.setKmsKeyReference("local");
        c.setEncryptedWebhookSecret(encrypt(secret));
    }

    @Override
    public DecryptedCredentials decryptTokens(Connection c) {
        return new DecryptedCredentials(
                decrypt(c.getEncryptedAccessToken()),
                decrypt(c.getEncryptedRefreshToken()),
                c.getTokenExpiresAt() != null ? c.getTokenExpiresAt().toInstant() : null,
                parseConfigJson(c.getConfigJson()));
    }

    @Override
    public String decryptWebhookSecret(Connection c) {
        return decrypt(c.getEncryptedWebhookSecret());
    }

    private String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
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
