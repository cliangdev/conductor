package com.conductor.integration.connector;

import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.connector.local.LocalPostHogConnector;
import com.conductor.integration.connector.posthog.PostHogConnector;
import com.conductor.integration.connector.posthog.model.PostHogHogQLResponse;
import com.conductor.integration.connector.posthog.model.PostHogTrendsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        assertThat(result.data()).containsKeys("visitors", "sessions", "topPages", "topSources");
        verifyNoInteractions(spyTemplate);
    }

    @Test
    void rateLimitedResponseReturnsDegradedWithoutRetry() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(PostHogTrendsResponse.class)))
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

    @Test
    void fetchDataIncludesSummaryMetrics() {
        RestTemplate restTemplate = mock(RestTemplate.class);

        PostHogTrendsResponse trendsResponse = new PostHogTrendsResponse(List.of(
            new PostHogTrendsResponse.PostHogTrendsResult(List.of(100), List.of("2026-06-01"))));
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(PostHogTrendsResponse.class)))
                .thenReturn(ResponseEntity.ok(trendsResponse));

        PostHogHogQLResponse visitorsSessionsResponse = new PostHogHogQLResponse(
            List.of(List.of(543, 234)), List.of());
        PostHogHogQLResponse bounceResponse = new PostHogHogQLResponse(
            List.of(List.of(42.0, 145.5)), List.of());
        PostHogHogQLResponse sourcesResponse = new PostHogHogQLResponse(
            List.of(List.of("$direct", 80)), List.of());
        PostHogHogQLResponse pagesResponse = new PostHogHogQLResponse(
            List.of(List.of("/", 543, 812)), List.of());

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(PostHogHogQLResponse.class)))
                .thenReturn(ResponseEntity.ok(visitorsSessionsResponse))
                .thenReturn(ResponseEntity.ok(bounceResponse))
                .thenReturn(ResponseEntity.ok(sourcesResponse))
                .thenReturn(ResponseEntity.ok(pagesResponse));

        PostHogConnector connector = new PostHogConnector(restTemplate);
        ConnectorData result = connector.fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(result.data()).containsKey("series");
        assertThat(result.data()).containsKey("total");
        assertThat(result.data().get("visitors")).isEqualTo(543L);
        assertThat(result.data().get("sessions")).isEqualTo(234L);
        assertThat((Double) result.data().get("bounceRate")).isEqualTo(0.42);
        assertThat(result.data().get("avgSessionDuration")).isEqualTo(145.5);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topSources = (List<Map<String, Object>>) result.data().get("topSources");
        assertThat(topSources).hasSize(1);
        assertThat(topSources.get(0).get("source")).isEqualTo("$direct");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topPages = (List<Map<String, Object>>) result.data().get("topPages");
        assertThat(topPages).hasSize(1);
        assertThat(topPages.get(0).get("path")).isEqualTo("/");
    }
}
