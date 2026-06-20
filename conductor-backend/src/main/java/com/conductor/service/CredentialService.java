package com.conductor.service;

import com.conductor.integration.AuthType;
import com.conductor.integration.DecryptedCredentials;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

public interface CredentialService {
    void storeCredentials(String projectId, String connectorId, AuthType authType,
                          String accessToken, String refreshToken, OffsetDateTime expiresAt,
                          Map<String, Object> configJson);

    Optional<DecryptedCredentials> getCredentials(String projectId, String connectorId);

    void updateAccessToken(String projectId, String connectorId,
                           String newAccessToken, OffsetDateTime newExpiresAt);

    void deleteCredentials(String projectId, String connectorId);
}
