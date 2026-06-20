package com.conductor.service;

import com.conductor.entity.IntegrationCredential;
import com.conductor.entity.IntegrationDataCache;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.IntegrationConnector;
import com.conductor.repository.IntegrationCredentialRepository;
import com.conductor.repository.IntegrationDataCacheRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationFetchServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String CONNECTOR_ID = "bigquery";

    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private CredentialService credentialService;
    @Mock
    private OAuthFlowService oAuthFlowService;
    @Mock
    private IntegrationDataCacheRepository cacheRepository;
    @Mock
    private IntegrationCredentialRepository credentialRepository;
    @Mock
    private IntegrationConnector connector;

    private ExecutorService executor;
    private IntegrationFetchService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        service = new IntegrationFetchService(
                connectorRegistry, credentialService, oAuthFlowService,
                cacheRepository, credentialRepository, new ObjectMapper(), executor);

        when(connectorRegistry.getById(CONNECTOR_ID)).thenReturn(Optional.of(connector));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private IntegrationDataCache staleCache(Map<String, Object> data) {
        IntegrationDataCache cache = new IntegrationDataCache();
        cache.setProjectId(PROJECT_ID);
        cache.setConnectorId(CONNECTOR_ID);
        try {
            cache.setDataJson(new ObjectMapper().writeValueAsString(data));
        } catch (Exception e) {
            cache.setDataJson("{}");
        }
        cache.setHealthStatus("HEALTHY");
        cache.setFetchedAt(OffsetDateTime.now().minusHours(2));
        return cache;
    }

    private void stubApiKeyCreds() {
        when(credentialService.getCredentials(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(Optional.of(new DecryptedCredentials("token", null, null, Map.of())));
        IntegrationCredential cred = new IntegrationCredential();
        cred.setAuthType("API_KEY");
        when(credentialRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(Optional.of(cred));
    }

    @Test
    void fetchFailure_returnsDegraded_andDoesNotOverwriteCache() {
        when(connector.getMaxCacheAge()).thenReturn(Duration.ofHours(1));
        IntegrationDataCache existing = staleCache(Map.of("rows", 42));
        when(cacheRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(Optional.of(existing));
        stubApiKeyCreds();
        when(connector.fetchData(any())).thenThrow(new RuntimeException("vendor down"));

        ConnectorData result = service.fetchData(PROJECT_ID, CONNECTOR_ID, false);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(result.data()).containsEntry("rows", 42);
        // Cache row health is updated to DEGRADED but data is never overwritten.
        verify(cacheRepository).save(existing);
        assertThat(existing.getDataJson()).contains("42");
        assertThat(existing.getHealthStatus()).isEqualTo("DEGRADED");
    }

    @Test
    void freshCache_returnsCache_withoutCallingConnector() {
        when(connector.getMaxCacheAge()).thenReturn(Duration.ofHours(1));
        IntegrationDataCache fresh = staleCache(Map.of("rows", 7));
        fresh.setFetchedAt(OffsetDateTime.now().minusMinutes(5));
        when(cacheRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(Optional.of(fresh));

        ConnectorData result = service.fetchData(PROJECT_ID, CONNECTOR_ID, false);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(result.data()).containsEntry("rows", 7);
        verify(connector, never()).fetchData(any());
        verify(cacheRepository, never()).save(any());
    }

    @Test
    void forceRefresh_callsConnector_evenWhenCacheIsFresh() {
        IntegrationDataCache fresh = staleCache(Map.of("rows", 7));
        fresh.setFetchedAt(OffsetDateTime.now().minusMinutes(5));
        when(cacheRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(Optional.of(fresh));
        stubApiKeyCreds();
        when(connector.fetchData(any())).thenReturn(ConnectorData.healthy(Map.of("rows", 99)));

        ConnectorData result = service.fetchData(PROJECT_ID, CONNECTOR_ID, true);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(result.data()).containsEntry("rows", 99);
        verify(connector).fetchData(any());
        verify(cacheRepository).save(any(IntegrationDataCache.class));
    }
}
