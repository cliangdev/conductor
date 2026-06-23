package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.ConnectionDataCache;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.FetchConnector;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationFetchServiceTest {

    private static final String CONNECTION_ID = "conn-1";
    private static final String PROJECT_ID = "proj-1";
    private static final String CONNECTOR_ID = "gcp-billing";

    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private ConnectionService connectionService;
    @Mock private OAuthFlowService oAuthFlowService;
    @Mock private ConnectionDataCacheRepository cacheRepository;
    @Mock private FetchConnector connector;

    private ExecutorService executor;
    private IntegrationFetchService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        service = new IntegrationFetchService(
                connectorRegistry, connectionService, oAuthFlowService,
                cacheRepository, new ObjectMapper(), executor);

        Connection conn = new Connection();
        conn.setId(CONNECTION_ID);
        conn.setProjectId(PROJECT_ID);
        conn.setConnectorId(CONNECTOR_ID);
        conn.setAuthType("API_KEY");
        lenient().when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(conn));
        lenient().when(connectorRegistry.findFetch(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        lenient().when(connectionService.toContext(conn)).thenReturn(
                new ConnectionContext(PROJECT_ID, CONNECTOR_ID, CONNECTION_ID, "token", null, null, Map.of(), null));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private ConnectionDataCache staleCache(Map<String, Object> data) {
        ConnectionDataCache cache = new ConnectionDataCache();
        cache.setConnectionId(CONNECTION_ID);
        try {
            cache.setDataJson(new ObjectMapper().writeValueAsString(data));
        } catch (Exception e) {
            cache.setDataJson("{}");
        }
        cache.setHealthStatus("HEALTHY");
        cache.setFetchedAt(OffsetDateTime.now().minusHours(2));
        return cache;
    }

    @Test
    void fetchFailure_returnsDegraded_andDoesNotOverwriteCache() {
        when(connector.getMaxCacheAge()).thenReturn(Duration.ofHours(1));
        ConnectionDataCache existing = staleCache(Map.of("rows", 42));
        when(cacheRepository.findByConnectionId(CONNECTION_ID)).thenReturn(Optional.of(existing));
        when(connector.fetchData(any())).thenThrow(new RuntimeException("vendor down"));

        ConnectorData result = service.fetchData(CONNECTION_ID, false);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(result.data()).containsEntry("rows", 42);
        verify(cacheRepository).save(existing);
        assertThat(existing.getDataJson()).contains("42");
        assertThat(existing.getHealthStatus()).isEqualTo("DEGRADED");
    }

    @Test
    void freshCache_returnsCache_withoutCallingConnector() {
        when(connector.getMaxCacheAge()).thenReturn(Duration.ofHours(1));
        ConnectionDataCache fresh = staleCache(Map.of("rows", 7));
        fresh.setFetchedAt(OffsetDateTime.now().minusMinutes(5));
        when(cacheRepository.findByConnectionId(CONNECTION_ID)).thenReturn(Optional.of(fresh));

        ConnectorData result = service.fetchData(CONNECTION_ID, false);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(result.data()).containsEntry("rows", 7);
        verify(connector, never()).fetchData(any());
        verify(cacheRepository, never()).save(any());
    }

    @Test
    void appAuth_withNullAccessToken_isNotShortCircuitedToSetupRequired() {
        // APP-auth connections (e.g. the GitHub App) mint tokens on demand and store no access token;
        // a null token must NOT be treated as "not connected" — the connector should still be invoked.
        Connection appConn = new Connection();
        appConn.setId(CONNECTION_ID);
        appConn.setProjectId(PROJECT_ID);
        appConn.setConnectorId(CONNECTOR_ID);
        appConn.setAuthType("APP");
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(appConn));
        when(connectionService.toContext(appConn)).thenReturn(
                new ConnectionContext(PROJECT_ID, CONNECTOR_ID, CONNECTION_ID, null, null, null, Map.of(), null));
        when(cacheRepository.findByConnectionId(CONNECTION_ID)).thenReturn(Optional.empty());
        when(connector.fetchData(any())).thenReturn(ConnectorData.healthy(Map.of("rows", 5)));

        ConnectorData result = service.fetchData(CONNECTION_ID, true);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(result.data()).containsEntry("rows", 5);
        verify(connector).fetchData(any());
    }

    @Test
    void apiKeyAuth_withNullAccessToken_returnsSetupRequired_withoutCallingConnector() {
        Connection apiKeyConn = new Connection();
        apiKeyConn.setId(CONNECTION_ID);
        apiKeyConn.setProjectId(PROJECT_ID);
        apiKeyConn.setConnectorId(CONNECTOR_ID);
        apiKeyConn.setAuthType("API_KEY");
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(apiKeyConn));
        when(connectionService.toContext(apiKeyConn)).thenReturn(
                new ConnectionContext(PROJECT_ID, CONNECTOR_ID, CONNECTION_ID, null, null, null, Map.of(), null));
        when(cacheRepository.findByConnectionId(CONNECTION_ID)).thenReturn(Optional.empty());

        ConnectorData result = service.fetchData(CONNECTION_ID, true);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        verify(connector, never()).fetchData(any());
    }

    @Test
    void forceRefresh_callsConnector_evenWhenCacheIsFresh() {
        ConnectionDataCache fresh = staleCache(Map.of("rows", 7));
        fresh.setFetchedAt(OffsetDateTime.now().minusMinutes(5));
        when(cacheRepository.findByConnectionId(CONNECTION_ID)).thenReturn(Optional.of(fresh));
        when(connector.fetchData(any())).thenReturn(ConnectorData.healthy(Map.of("rows", 99)));

        ConnectorData result = service.fetchData(CONNECTION_ID, true);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(result.data()).containsEntry("rows", 99);
        verify(connector).fetchData(any());
        verify(cacheRepository).save(any(ConnectionDataCache.class));
    }
}
