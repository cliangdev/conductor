package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.ConnectionDataCache;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.FetchConnector;
import com.conductor.integration.OAuthReauthRequiredException;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
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
        // The failure reason is persisted so the GET path can replay it on the next page load.
        assertThat(result.errorMessage()).contains("vendor down");
        assertThat(existing.getErrorMessage()).contains("vendor down");
    }

    @Test
    void firstFetchFailure_withNoCache_persistsDegradedRowCarryingTheError() {
        // forceRefresh=true with no cache never consults getMaxCacheAge — don't stub it (strict stubs).
        when(cacheRepository.findByConnectionId(CONNECTION_ID)).thenReturn(Optional.empty());
        when(connector.fetchData(any())).thenThrow(new RuntimeException("vendor down"));

        ConnectorData result = service.fetchData(CONNECTION_ID, true);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        // With no prior cache there is nothing to fall back to — but we still persist a row so the
        // error survives to the next GET (loss point: the GET path previously had nothing to read).
        org.mockito.ArgumentCaptor<ConnectionDataCache> captor =
                org.mockito.ArgumentCaptor.forClass(ConnectionDataCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getHealthStatus()).isEqualTo("DEGRADED");
        assertThat(captor.getValue().getErrorMessage()).contains("vendor down");
    }

    @Test
    void freshCache_replaysPersistedErrorMessage() {
        when(connector.getMaxCacheAge()).thenReturn(Duration.ofHours(1));
        ConnectionDataCache fresh = staleCache(Map.of("rows", 7));
        fresh.setFetchedAt(OffsetDateTime.now().minusMinutes(5));
        fresh.setHealthStatus("DEGRADED");
        fresh.setErrorMessage("BigQuery error: table not found");
        when(cacheRepository.findByConnectionId(CONNECTION_ID)).thenReturn(Optional.of(fresh));

        ConnectorData result = service.fetchData(CONNECTION_ID, false);

        // Serving cache without a live fetch must carry the stored error through, not null it out.
        assertThat(result.errorMessage()).isEqualTo("BigQuery error: table not found");
        verify(connector, never()).fetchData(any());
    }

    @Test
    void successfulFetch_clearsAnyPreviousError() {
        ConnectionDataCache existing = staleCache(Map.of("rows", 1));
        existing.setErrorMessage("stale failure");
        when(cacheRepository.findByConnectionId(CONNECTION_ID)).thenReturn(Optional.of(existing));
        when(connector.fetchData(any())).thenReturn(ConnectorData.healthy(Map.of("rows", 99)));

        service.fetchData(CONNECTION_ID, true);

        assertThat(existing.getErrorMessage()).isNull();
        assertThat(existing.getHealthStatus()).isEqualTo("HEALTHY");
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
    void oauthRefreshPermanentlyDead_returnsSetupRequired_withoutCallingConnector() {
        Connection oauthConn = new Connection();
        oauthConn.setId(CONNECTION_ID);
        oauthConn.setProjectId(PROJECT_ID);
        oauthConn.setConnectorId(CONNECTOR_ID);
        oauthConn.setAuthType("OAUTH2");
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(oauthConn));
        when(connectionService.toContext(oauthConn)).thenReturn(new ConnectionContext(
                PROJECT_ID, CONNECTOR_ID, CONNECTION_ID, "stale-token", "refresh-token",
                Instant.now().minusSeconds(60), Map.of(), null));
        when(cacheRepository.findByConnectionId(CONNECTION_ID)).thenReturn(Optional.empty());
        when(oAuthFlowService.refreshAccessToken(oauthConn, "refresh-token"))
                .thenThrow(new OAuthReauthRequiredException("refresh token dead", null));

        ConnectorData result = service.fetchData(CONNECTION_ID, true);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        assertThat(result.errorMessage()).contains("reconnect");
        // Never fetches with a token we already know is dead, and never touches the cache — matches
        // the existing "not connected" short-circuit above.
        verify(connector, never()).fetchData(any());
        verify(cacheRepository, never()).save(any());
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
