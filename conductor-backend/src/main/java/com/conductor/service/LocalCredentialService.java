package com.conductor.service;

import com.conductor.entity.IntegrationCredential;
import com.conductor.integration.AuthType;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.repository.IntegrationCredentialRepository;
import com.conductor.repository.IntegrationDataCacheRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@Profile("local")
public class LocalCredentialService implements CredentialService {

    private static final Logger log = LoggerFactory.getLogger(LocalCredentialService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final IntegrationCredentialRepository credentialRepository;
    private final IntegrationDataCacheRepository cacheRepository;
    private final ObjectMapper objectMapper;
    private final SecretKey secretKey;

    public LocalCredentialService(
            IntegrationCredentialRepository credentialRepository,
            IntegrationDataCacheRepository cacheRepository,
            ObjectMapper objectMapper,
            @Value("${conductor.integration.local-encryption-key:dev-only-32-byte-key-not-4-prod}") String localKey) {
        this.credentialRepository = credentialRepository;
        this.cacheRepository = cacheRepository;
        this.objectMapper = objectMapper;
        // Pad or truncate to exactly 32 bytes for AES-256
        byte[] keyBytes = Arrays.copyOf(localKey.getBytes(StandardCharsets.UTF_8), 32);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    private String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // Prepend IV to ciphertext
            byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv).put(ciphertext).array();
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

    @Override
    @Transactional
    public void storeCredentials(String projectId, String connectorId, AuthType authType,
                                  String accessToken, String refreshToken, OffsetDateTime expiresAt,
                                  Map<String, Object> configJson) {
        log.info("Storing credentials for connector={} project={}", connectorId, projectId);
        // Never log accessToken or refreshToken

        IntegrationCredential cred = credentialRepository
                .findByProjectIdAndConnectorId(projectId, connectorId)
                .orElse(new IntegrationCredential());

        cred.setProjectId(projectId);
        cred.setConnectorId(connectorId);
        cred.setAuthType(authType.name());
        cred.setEncryptedAccessToken(encrypt(accessToken));
        cred.setEncryptedRefreshToken(encrypt(refreshToken));
        cred.setKmsKeyReference("local");
        cred.setTokenExpiresAt(expiresAt);

        if (configJson != null && !configJson.isEmpty()) {
            try {
                cred.setConfigJson(objectMapper.writeValueAsString(configJson));
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize configJson", e);
            }
        }

        credentialRepository.save(cred);
    }

    @Override
    public Optional<DecryptedCredentials> getCredentials(String projectId, String connectorId) {
        return credentialRepository.findByProjectIdAndConnectorId(projectId, connectorId)
                .map(cred -> {
                    Map<String, Object> configJson = parseConfigJson(cred.getConfigJson());
                    return new DecryptedCredentials(
                            decrypt(cred.getEncryptedAccessToken()),
                            decrypt(cred.getEncryptedRefreshToken()),
                            cred.getTokenExpiresAt() != null ? cred.getTokenExpiresAt().toInstant() : null,
                            configJson
                    );
                });
    }

    @Override
    @Transactional
    public void updateAccessToken(String projectId, String connectorId,
                                   String newAccessToken, OffsetDateTime newExpiresAt) {
        credentialRepository.findByProjectIdAndConnectorId(projectId, connectorId)
                .ifPresent(cred -> {
                    cred.setEncryptedAccessToken(encrypt(newAccessToken));
                    cred.setTokenExpiresAt(newExpiresAt);
                    credentialRepository.save(cred);
                });
    }

    @Override
    @Transactional
    public void deleteCredentials(String projectId, String connectorId) {
        credentialRepository.deleteByProjectIdAndConnectorId(projectId, connectorId);
        cacheRepository.deleteByProjectIdAndConnectorId(projectId, connectorId);
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
