package com.conductor.integration.connector;

import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.connector.local.LocalRevenueCatConnector;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RevenueCatConnectorTest {

    private static final ConnectionContext CREDS = new ConnectionContext(
            "proj", "revenuecat", "conn", "sk-key", null, null,
            Map.of("projectId", "proj_abc", "currency", "USD"), null);

    @Test
    @SuppressWarnings("unchecked")
    void overviewConvertsCentsToDollarsAndSeriesUseValueKey() {
        RestTemplate restTemplate = mock(RestTemplate.class);

        // Overview: mrr and revenue are in cents.
        Map<String, Object> overviewBody = Map.of(
                "active_trials", 10,
                "active_subscriptions", 200,
                "mrr", 123456,                 // → 1234.56
                "revenue_last_28_days", 654321, // → 6543.21
                "new_customers_last_28_days", 42,
                "active_users_last_28_days", 1000);
        when(restTemplate.exchange(contains("/metrics/overview"), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(overviewBody));

        // new_customers chart: {period, value}.
        Map<String, Object> customersBody = Map.of("values", List.of(
                Map.of("period", "2026-06-01", "value", 5),
                Map.of("period", "2026-06-02", "value", 7)));
        when(restTemplate.exchange(contains("/charts/new_customers"), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(customersBody));

        // new_trials chart.
        when(restTemplate.exchange(contains("/charts/new_trials"), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("values", List.of(
                        Map.of("period", "2026-06-01", "value", 2)))));

        // trial_conversion chart.
        when(restTemplate.exchange(contains("/charts/trial_conversion"), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("values", List.of(
                        Map.of("period", "2026-06-01", "start_rate", 1.0, "conversion_rate", 0.45)))));

        // revenue chart: values in cents.
        when(restTemplate.exchange(contains("/charts/revenue"), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("values", List.of(
                        Map.of("period", "2026-06-01", "value", 5000))))); // → 50.00

        RevenueCatConnector connector = new RevenueCatConnector(restTemplate);
        ConnectorData result = connector.fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);

        Map<String, Object> overview = (Map<String, Object>) result.data().get("overview");
        assertThat((Double) overview.get("mrr")).isEqualTo(1234.56);
        assertThat((Double) overview.get("revenueLast28Days")).isEqualTo(6543.21);
        assertThat(overview.get("activeTrials")).isEqualTo(10L);
        assertThat(overview.get("activeSubscriptions")).isEqualTo(200L);
        assertThat(overview.get("newCustomersLast28Days")).isEqualTo(42L);

        List<Map<String, Object>> customers = (List<Map<String, Object>>) result.data().get("newCustomersSeries");
        assertThat(customers).hasSize(2);
        assertThat(customers.get(0)).containsKeys("date", "value");
        assertThat(customers.get(0)).doesNotContainKey("count");
        assertThat(customers.get(0).get("date")).isEqualTo("2026-06-01");
        assertThat((Double) customers.get(0).get("value")).isEqualTo(5.0);

        List<Map<String, Object>> revenue = (List<Map<String, Object>>) result.data().get("revenueSeries");
        assertThat((Double) revenue.get(0).get("value")).isEqualTo(50.0);

        List<Map<String, Object>> conversion = (List<Map<String, Object>>) result.data().get("trialConversion");
        assertThat(conversion.get(0)).containsKeys("period", "startRate", "conversionRate");
        assertThat((Double) conversion.get(0).get("conversionRate")).isEqualTo(0.45);
    }

    @Test
    void missingProjectIdReturnsSetupRequired() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RevenueCatConnector connector = new RevenueCatConnector(restTemplate);

        ConnectorData result = connector.fetchData(
                new ConnectionContext("proj", "revenuecat", "conn", "sk-key", null, null, Map.of(), null));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        verifyNoInteractions(restTemplate);
    }

    @Test
    void unauthorizedOverviewReturnsDegradedCheckCredentials() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(contains("/metrics/overview"), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        RevenueCatConnector connector = new RevenueCatConnector(restTemplate);
        ConnectorData result = connector.fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(result.errorMessage()).contains("Check credentials");
    }

    @Test
    @SuppressWarnings("unchecked")
    void localConnectorReturnsHealthyFakeDataWithNoHttpCalls() {
        RestTemplate spyTemplate = mock(RestTemplate.class);
        LocalRevenueCatConnector connector = new LocalRevenueCatConnector();

        ConnectorData result = connector.fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(result.data().get("overview")).isNotNull();
        List<Map<String, Object>> customers = (List<Map<String, Object>>) result.data().get("newCustomersSeries");
        assertThat(customers).hasSize(30);
        assertThat(customers.get(0)).containsKeys("date", "value");
        verifyNoInteractions(spyTemplate);
    }
}
