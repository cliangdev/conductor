package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.ConnectionDataCache;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.FetchConnector;
import com.conductor.repository.ConnectionDataCacheRepository;
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

/**
 * Pull pipeline keyed by connection. Returns cached data if fresh; otherwise runs the connector's
 * fetch on a bounded thread (timeout → DEGRADED with stale data) and upserts the per-connection cache.
 * Never throws on fetch failure.
 */
@Service
public class IntegrationFetchService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationFetchService.class);
    private static final int FETCH_TIMEOUT_SECONDS = 10;
    private static final int TOKEN_REFRESH_BUFFER_MINUTES = 5;

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionService connectionService;
    private final OAuthFlowService oAuthFlowService;
    private final ConnectionDataCacheRepository cacheRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService fetchExecutor;

    public IntegrationFetchService(
            ConnectorRegistry connectorRegistry,
            ConnectionService connectionService,
            OAuthFlowService oAuthFlowService,
            ConnectionDataCacheRepository cacheRepository,
            ObjectMapper objectMapper,
            @Qualifier("integrationFetchExecutor") ExecutorService fetchExecutor) {
        this.connectorRegistry = connectorRegistry;
        this.connectionService = connectionService;
        this.oAuthFlowService = oAuthFlowService;
        this.cacheRepository = cacheRepository;
        this.objectMapper = objectMapper;
        this.fetchExecutor = fetchExecutor;
    }

    /** Fetches data for a single connection. Never throws — returns ConnectorData (possibly DEGRADED). */
    public ConnectorData fetchData(String connectionId, boolean forceRefresh) {
        Connection conn = connectionService.getById(connectionId)
                .orElseThrow(() -> new EntityNotFoundException("Connection not found: " + connectionId));
        FetchConnector connector = connectorRegistry.findFetch(conn.getConnectorId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Connector does not support fetch: " + conn.getConnectorId()));

        Optional<ConnectionDataCache> cached = cacheRepository.findByConnectionId(connectionId);

        if (!forceRefresh && cached.isPresent()) {
            ConnectionDataCache cache = cached.get();
            OffsetDateTime maxAge = cache.getFetchedAt().plus(connector.getMaxCacheAge());
            if (OffsetDateTime.now().isBefore(maxAge)) {
                log.debug("Returning fresh cache for connection={}", connectionId);
                return cacheToConnectorData(cache);
            }
        }

        ConnectionContext ctx = connectionService.toContext(conn);
        // WEBHOOK (signing-secret) and APP (mints tokens on demand, stores none — e.g. the GitHub App)
        // connections legitimately carry no stored access token, so a null token here is not "not connected".
        if (ctx.accessToken() == null && !storesNoAccessToken(conn.getAuthType())) {
            return ConnectorData.setupRequired("Integration not connected — add credentials in Settings");
        }
        ctx = maybeRefreshToken(conn, ctx);

        final ConnectionContext finalCtx = ctx;
        ConnectorData result;
        try {
            Future<ConnectorData> future = fetchExecutor.submit(() -> connector.fetchData(finalCtx));
            result = future.get(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Fetch timeout for connection={}", connectionId);
            result = ConnectorData.degraded("Fetch timed out after " + FETCH_TIMEOUT_SECONDS + "s",
                    extractStaleData(cached));
        } catch (Exception e) {
            log.warn("Fetch failed for connection={}: {}", connectionId, e.getMessage());
            result = ConnectorData.degraded("Fetch failed: " + e.getMessage(), extractStaleData(cached));
        }

        if (result.healthStatus() == ConnectorHealth.HEALTHY) {
            upsertCache(connectionId, result, cached.orElse(null));
        } else if (result.healthStatus() == ConnectorHealth.DEGRADED && cached.isPresent()) {
            ConnectionDataCache cache = cached.get();
            cache.setHealthStatus(result.healthStatus().name());
            cacheRepository.save(cache);
        }

        return result;
    }

    /** Auth types whose connections never persist an access token, so a null token is not "setup required". */
    private boolean storesNoAccessToken(String authType) {
        return AuthType.WEBHOOK.name().equals(authType) || AuthType.APP.name().equals(authType);
    }

    private ConnectionContext maybeRefreshToken(Connection conn, ConnectionContext ctx) {
        boolean isOAuth = AuthType.OAUTH2.name().equals(conn.getAuthType());
        if (!isOAuth || ctx.expiresAt() == null) {
            return ctx;
        }
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(ctx.expiresAt(), ZoneOffset.UTC);
        if (OffsetDateTime.now().plusMinutes(TOKEN_REFRESH_BUFFER_MINUTES).isAfter(expiresAt)) {
            try {
                String newToken = oAuthFlowService.refreshAccessToken(conn, ctx.refreshToken());
                return new ConnectionContext(ctx.projectId(), ctx.connectorId(), ctx.connectionId(),
                        newToken, ctx.refreshToken(), ctx.expiresAt(), ctx.config(), ctx.webhookSecret());
            } catch (Exception e) {
                log.warn("Token refresh failed for connection={}: {}", conn.getId(), e.getMessage());
            }
        }
        return ctx;
    }

    private void upsertCache(String connectionId, ConnectorData data, ConnectionDataCache existing) {
        ConnectionDataCache cache = existing != null ? existing : new ConnectionDataCache();
        cache.setConnectionId(connectionId);
        try {
            cache.setDataJson(objectMapper.writeValueAsString(data.data()));
        } catch (Exception e) {
            cache.setDataJson("{}");
        }
        cache.setHealthStatus(data.healthStatus().name());
        cache.setFetchedAt(OffsetDateTime.now());
        cacheRepository.save(cache);
    }

    private ConnectorData cacheToConnectorData(ConnectionDataCache cache) {
        Map<String, Object> data = parseJson(cache.getDataJson());
        ConnectorHealth health = ConnectorHealth.valueOf(cache.getHealthStatus());
        return new ConnectorData(data, health, cache.getFetchedAt().toInstant(), null);
    }

    private Map<String, Object> extractStaleData(Optional<ConnectionDataCache> cached) {
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
}
