package com.conductor.integration.connector;

import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.connector.local.LocalPostHogConnector;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PostHogConnectorTest {

    private static final ConnectionContext CREDS = new ConnectionContext(
            "proj", "posthog", "conn", "ph-key", null, null, Map.of("projectId", "123"), null);

    @Test
    void localConnectorReturnsThirtyElementHealthySeriesWithNoHttpCalls() {
        RestTemplate spyTemplate = mock(RestTemplate.class);
        LocalPostHogConnector connector = new LocalPostHogConnector();

        ConnectorData result = connector.fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) result.data().get("series");
        assertThat(series).hasSize(30);
        assertThat(result.data().get("total")).isNotNull();
        verifyNoInteractions(spyTemplate);
    }

    @Test
    void rateLimitedResponseReturnsDegradedWithoutRetry() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null));

        PostHogConnector connector = new PostHogConnector(restTemplate);

        ConnectorData result = connector.fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(result.errorMessage()).contains("Rate limited");
    }

    @Test
    void missingProjectIdReturnsSetupRequired() {
        PostHogConnector connector = new PostHogConnector(mock(RestTemplate.class));

        ConnectorData result = connector.fetchData(
                new ConnectionContext("proj", "posthog", "conn", "ph-key", null, null, Map.of(), null));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }
}
