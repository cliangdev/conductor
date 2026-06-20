package com.conductor.service;

import com.conductor.entity.IntegrationCredential;
import com.conductor.integration.AuthType;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.repository.IntegrationCredentialRepository;
import com.conductor.repository.IntegrationDataCacheRepository;
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
import org.springframework.transaction.annotation.Transactional;

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

@Service
@Profile("!local")
public class GcpKmsCredentialService implements CredentialService {

    private static final Logger log = LoggerFactory.getLogger(GcpKmsCredentialService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final IntegrationCredentialRepository credentialRepository;
    private final IntegrationDataCacheRepository cacheRepository;
    private final ObjectMapper objectMapper;
    private final String kmsKeyName;

    public GcpKmsCredentialService(
            IntegrationCredentialRepository credentialRepository,
            IntegrationDataCacheRepository cacheRepository,
            ObjectMapper objectMapper,
            @Value("${GCP_PROJECT_ID:}") String gcpProjectId,
            @Value("${conductor.kms.location:global}") String kmsLocation,
            @Value("${conductor.kms.key-ring:conductor-secrets}") String kmsKeyRing,
            @Value("${conductor.kms.key-name:integration-credentials-kek}") String kmsKeyName) {
        this.credentialRepository = credentialRepository;
        this.cacheRepository = cacheRepository;
        this.objectMapper = objectMapper;
        this.kmsKeyName = CryptoKeyName.format(gcpProjectId, kmsLocation, kmsKeyRing, kmsKeyName);
    }

    // Encrypt plaintext using AES-256-GCM with the provided DEK
    private byte[] encryptWithDek(byte[] dek, String plaintext) throws Exception {
        if (plaintext == null) return null;
        SecretKey key = new SecretKeySpec(dek, "AES");
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
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

    @Override
    @Transactional
    public void storeCredentials(String projectId, String connectorId, AuthType authType,
                                  String accessToken, String refreshToken, OffsetDateTime expiresAt,
                                  Map<String, Object> configJson) {
        log.info("Storing credentials for connector={} project={}", connectorId, projectId);
        try (KeyManagementServiceClient kmsClient = KeyManagementServiceClient.create()) {
            // Generate a new DEK
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            byte[] dek = keyGen.generateKey().getEncoded();

            // Wrap DEK with KMS KEK
            EncryptResponse encrypted = kmsClient.encrypt(kmsKeyName, ByteString.copyFrom(dek));
            String wrappedDek = Base64.getEncoder().encodeToString(encrypted.getCiphertext().toByteArray());

            // Encrypt tokens with DEK
            byte[] encAccessBytes = accessToken != null ? encryptWithDek(dek, accessToken) : null;
            byte[] encRefreshBytes = refreshToken != null ? encryptWithDek(dek, refreshToken) : null;

            IntegrationCredential cred = credentialRepository
                    .findByProjectIdAndConnectorId(projectId, connectorId)
                    .orElse(new IntegrationCredential());

            cred.setProjectId(projectId);
            cred.setConnectorId(connectorId);
            cred.setAuthType(authType.name());
            cred.setEncryptedAccessToken(encAccessBytes != null ? Base64.getEncoder().encodeToString(encAccessBytes) : null);
            cred.setEncryptedRefreshToken(encRefreshBytes != null ? Base64.getEncoder().encodeToString(encRefreshBytes) : null);
            cred.setKmsKeyReference(wrappedDek);
            cred.setTokenExpiresAt(expiresAt);

            if (configJson != null && !configJson.isEmpty()) {
                cred.setConfigJson(objectMapper.writeValueAsString(configJson));
            }
            credentialRepository.save(cred);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store credentials securely", e);
        }
    }

    @Override
    public Optional<DecryptedCredentials> getCredentials(String projectId, String connectorId) {
        return credentialRepository.findByProjectIdAndConnectorId(projectId, connectorId)
                .map(cred -> {
                    try (KeyManagementServiceClient kmsClient = KeyManagementServiceClient.create()) {
                        byte[] wrappedDek = Base64.getDecoder().decode(cred.getKmsKeyReference());
                        DecryptResponse decryptResponse = kmsClient.decrypt(kmsKeyName, ByteString.copyFrom(wrappedDek));
                        byte[] dek = decryptResponse.getPlaintext().toByteArray();

                        String accessToken = decryptWithDek(dek, cred.getEncryptedAccessToken());
                        String refreshToken = decryptWithDek(dek, cred.getEncryptedRefreshToken());
                        Map<String, Object> configJson = parseConfigJson(cred.getConfigJson());

                        return new DecryptedCredentials(
                                accessToken, refreshToken,
                                cred.getTokenExpiresAt() != null ? cred.getTokenExpiresAt().toInstant() : null,
                                configJson);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to decrypt credentials", e);
                    }
                });
    }

    @Override
    @Transactional
    public void updateAccessToken(String projectId, String connectorId,
                                   String newAccessToken, OffsetDateTime newExpiresAt) {
        credentialRepository.findByProjectIdAndConnectorId(projectId, connectorId)
                .ifPresent(cred -> {
                    try (KeyManagementServiceClient kmsClient = KeyManagementServiceClient.create()) {
                        byte[] wrappedDek = Base64.getDecoder().decode(cred.getKmsKeyReference());
                        DecryptResponse decryptResponse = kmsClient.decrypt(kmsKeyName, ByteString.copyFrom(wrappedDek));
                        byte[] dek = decryptResponse.getPlaintext().toByteArray();

                        byte[] encAccessBytes = encryptWithDek(dek, newAccessToken);
                        cred.setEncryptedAccessToken(Base64.getEncoder().encodeToString(encAccessBytes));
                        cred.setTokenExpiresAt(newExpiresAt);
                        credentialRepository.save(cred);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to update access token", e);
                    }
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
