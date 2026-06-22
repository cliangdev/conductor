package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.repository.ConnectionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns Connection persistence + crypto orchestration. The single source of truth for a connector's
 * connected instances. Crypto is delegated to {@link CredentialService}; this service decides
 * single- vs multi-instance based on the connector's {@code ConnectorSpec.singleInstance()}.
 */
@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    private final ConnectionRepository connectionRepository;
    private final ConnectionDataCacheRepository cacheRepository;
    private final CredentialService credentialService;
    private final ConnectorRegistry connectorRegistry;
    private final ObjectMapper objectMapper;

    public ConnectionService(ConnectionRepository connectionRepository,
                             ConnectionDataCacheRepository cacheRepository,
                             CredentialService credentialService,
                             ConnectorRegistry connectorRegistry,
                             ObjectMapper objectMapper) {
        this.connectionRepository = connectionRepository;
        this.cacheRepository = cacheRepository;
        this.credentialService = credentialService;
        this.connectorRegistry = connectorRegistry;
        this.objectMapper = objectMapper;
    }

    public List<Connection> list(String projectId, String connectorId) {
        return connectionRepository.findByProjectIdAndConnectorId(projectId, connectorId);
    }

    public List<Connection> listForProject(String projectId) {
        return connectionRepository.findByProjectId(projectId);
    }

    public Optional<Connection> getById(String connectionId) {
        return connectionRepository.findById(connectionId);
    }

    public Optional<Connection> getById(String connectionId, String connectorId) {
        return connectionRepository.findByIdAndConnectorId(connectionId, connectorId);
    }

    /** Single-instance connectors: the one connection for (project, connector), if connected. */
    public Optional<Connection> findSingle(String projectId, String connectorId) {
        return list(projectId, connectorId).stream().findFirst();
    }

    public boolean isSingleInstance(String connectorId) {
        return connectorRegistry.getById(connectorId)
                .map(c -> c.getSpec().singleInstance())
                .orElse(true);
    }

    /** Get the existing single connection or create a fresh one (single-instance connect flow). */
    @Transactional
    public Connection getOrCreateSingle(String projectId, String connectorId, AuthType authType) {
        return findSingle(projectId, connectorId).orElseGet(() ->
                create(projectId, connectorId, authType, connectorId, null));
    }

    @Transactional
    public Connection create(String projectId, String connectorId, AuthType authType,
                             String displayLabel, String connectedBy) {
        Connection c = new Connection();
        c.setProjectId(projectId);
        c.setConnectorId(connectorId);
        c.setAuthType(authType.name());
        c.setDisplayLabel(displayLabel);
        c.setConnectedBy(connectedBy);
        c.setStatus("ACTIVE");
        return connectionRepository.save(c);
    }

    @Transactional
    public void storeTokens(Connection c, String accessToken, String refreshToken, OffsetDateTime expiresAt) {
        credentialService.putTokens(c, accessToken, refreshToken, expiresAt);
        c.setStatus("ACTIVE");
        connectionRepository.save(c);
    }

    @Transactional
    public void storeWebhookSecret(Connection c, String secret) {
        credentialService.putWebhookSecret(c, secret);
        c.setStatus("ACTIVE");
        connectionRepository.save(c);
    }

    /** Update only the access token (preserving the refresh token), reusing the same DEK. */
    @Transactional
    public void updateAccessToken(Connection c, String newAccessToken, OffsetDateTime newExpiresAt) {
        DecryptedCredentials current = credentialService.decryptTokens(c);
        credentialService.putTokens(c, newAccessToken, current.refreshToken(), newExpiresAt);
        connectionRepository.save(c);
    }

    @Transactional
    public void updateConfig(Connection c, Map<String, Object> config) {
        Map<String, Object> existing = parseConfig(c.getConfigJson());
        existing.putAll(config);
        try {
            c.setConfigJson(objectMapper.writeValueAsString(existing));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize config", e);
        }
        connectionRepository.save(c);
    }

    @Transactional
    public void updateLabel(Connection c, String label) {
        c.setDisplayLabel(label);
        connectionRepository.save(c);
    }

    @Transactional
    public void delete(String connectionId) {
        cacheRepository.deleteByConnectionId(connectionId);
        connectionRepository.deleteById(connectionId);
    }

    public ConnectionContext toContext(Connection c) {
        DecryptedCredentials creds = credentialService.decryptTokens(c);
        String webhookSecret = credentialService.decryptWebhookSecret(c);
        return ConnectionContext.of(c.getProjectId(), c.getConnectorId(), c.getId(), creds, webhookSecret);
    }

    public DecryptedCredentials decrypt(Connection c) {
        return credentialService.decryptTokens(c);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
