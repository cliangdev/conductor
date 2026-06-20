package com.conductor.service;

import com.conductor.entity.IntegrationDataCache;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.IntegrationConnector;
import com.conductor.repository.IntegrationCredentialRepository;
import com.conductor.repository.IntegrationDataCacheRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class IntegrationFetchService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationFetchService.class);
    private static final int FETCH_TIMEOUT_SECONDS = 10;
    private static final int TOKEN_REFRESH_BUFFER_MINUTES = 5;

    private final ConnectorRegistry connectorRegistry;
    private final CredentialService credentialService;
    private final OAuthFlowService oAuthFlowService;
    private final IntegrationDataCacheRepository cacheRepository;
    private final IntegrationCredentialRepository credentialRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService fetchExecutor;

    public IntegrationFetchService(
            ConnectorRegistry connectorRegistry,
            CredentialService credentialService,
            OAuthFlowService oAuthFlowService,
            IntegrationDataCacheRepository cacheRepository,
            IntegrationCredentialRepository credentialRepository,
            ObjectMapper objectMapper,
            @Qualifier("integrationFetchExecutor") ExecutorService fetchExecutor) {
        this.connectorRegistry = connectorRegistry;
        this.credentialService = credentialService;
        this.oAuthFlowService = oAuthFlowService;
        this.cacheRepository = cacheRepository;
        this.credentialRepository = credentialRepository;
        this.objectMapper = objectMapper;
        this.fetchExecutor = fetchExecutor;
    }

    /**
     * Fetches integration data. Returns cached data if fresh; otherwise triggers an on-demand fetch.
     * Never throws on fetch failure — returns ConnectorData, possibly DEGRADED with stale data.
     */
    public ConnectorData fetchData(String projectId, String connectorId, boolean forceRefresh) {
        IntegrationConnector connector = connectorRegistry.getById(connectorId)
                .orElseThrow(() -> new EntityNotFoundException("Connector not found: " + connectorId));

        Optional<IntegrationDataCache> cached =
                cacheRepository.findByProjectIdAndConnectorId(projectId, connectorId);

        if (!forceRefresh && cached.isPresent()) {
            IntegrationDataCache cache = cached.get();
            OffsetDateTime maxAge = cache.getFetchedAt().plus(connector.getMaxCacheAge());
            if (OffsetDateTime.now().isBefore(maxAge)) {
                log.debug("Returning fresh cache for connector={} project={}", connectorId, projectId);
                return cacheToConnectorData(cache);
            }
        }

        Optional<DecryptedCredentials> credsOpt = credentialService.getCredentials(projectId, connectorId);
        if (credsOpt.isEmpty()) {
            return ConnectorData.setupRequired("Integration not connected — add credentials in Settings");
        }
        DecryptedCredentials creds = maybeRefreshToken(projectId, connectorId, credsOpt.get());

        final DecryptedCredentials finalCreds = creds;
        ConnectorData result;
        try {
            Future<ConnectorData> future = fetchExecutor.submit(() -> connector.fetchData(finalCreds));
            result = future.get(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Fetch timeout for connector={} project={}", connectorId, projectId);
            result = ConnectorData.degraded("Fetch timed out after " + FETCH_TIMEOUT_SECONDS + "s",
                    extractStaleData(cached));
        } catch (Exception e) {
            log.warn("Fetch failed for connector={} project={}: {}", connectorId, projectId, e.getMessage());
            result = ConnectorData.degraded("Fetch failed: " + e.getMessage(), extractStaleData(cached));
        }

        if (result.healthStatus() == ConnectorHealth.HEALTHY) {
            upsertCache(projectId, connectorId, result, cached.orElse(null));
        } else if (result.healthStatus() == ConnectorHealth.DEGRADED && cached.isPresent()) {
            IntegrationDataCache cache = cached.get();
            cache.setHealthStatus(result.healthStatus().name());
            cacheRepository.save(cache);
        }

        return result;
    }

    private DecryptedCredentials maybeRefreshToken(String projectId, String connectorId,
                                                   DecryptedCredentials creds) {
        boolean isOAuth = AuthType.OAUTH2.name().equals(getAuthType(projectId, connectorId));
        if (!isOAuth || creds.expiresAt() == null) {
            return creds;
        }
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(creds.expiresAt(), ZoneOffset.UTC);
        if (OffsetDateTime.now().plusMinutes(TOKEN_REFRESH_BUFFER_MINUTES).isAfter(expiresAt)) {
            try {
                String newToken = oAuthFlowService.refreshAccessToken(projectId, connectorId, creds.refreshToken());
                return new DecryptedCredentials(newToken, creds.refreshToken(), creds.expiresAt(), creds.configJson());
            } catch (Exception e) {
                log.warn("Token refresh failed for connector={}: {}", connectorId, e.getMessage());
            }
        }
        return creds;
    }

    private void upsertCache(String projectId, String connectorId, ConnectorData data,
                             IntegrationDataCache existing) {
        IntegrationDataCache cache = existing != null ? existing : new IntegrationDataCache();
        cache.setProjectId(projectId);
        cache.setConnectorId(connectorId);
        try {
            cache.setDataJson(objectMapper.writeValueAsString(data.data()));
        } catch (Exception e) {
            cache.setDataJson("{}");
        }
        cache.setHealthStatus(data.healthStatus().name());
        cache.setFetchedAt(OffsetDateTime.now());
        cacheRepository.save(cache);
    }

    private ConnectorData cacheToConnectorData(IntegrationDataCache cache) {
        Map<String, Object> data = parseJson(cache.getDataJson());
        ConnectorHealth health = ConnectorHealth.valueOf(cache.getHealthStatus());
        return new ConnectorData(data, health, cache.getFetchedAt().toInstant(), null);
    }

    private Map<String, Object> extractStaleData(Optional<IntegrationDataCache> cached) {
        return cached.map(c -> parseJson(c.getDataJson())).orElse(Map.of());
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String getAuthType(String projectId, String connectorId) {
        return credentialRepository.findByProjectIdAndConnectorId(projectId, connectorId)
                .map(c -> c.getAuthType())
                .orElse("");
    }
}
