package com.conductor.integration.connector;

import com.conductor.entity.Connection;
import com.conductor.entity.ConnectionDataCache;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.connector.local.LocalRevenueCatConnector;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.IntegrationFetchService;
import com.conductor.service.OAuthFlowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RevenueCatConnectorTest {

    private static final ConnectionContext CREDS = new ConnectionContext(
            "proj", "revenuecat", "conn", "sk-key", null, null,
            Map.of("projectId", "proj_abc", "currency", "USD"), null);

    private static final String NO_DATA_MESSAGE =
            "RevenueCat returned no data for project proj_abc. Check the project ID, or disconnect it "
                    + "if this app does not bill through RevenueCat.";

    private static URI pathContaining(String fragment) {
        return argThat((URI uri) -> uri != null && uri.getRawPath().contains(fragment));
    }

    private static ConnectionContext contextFor(String projectId) {
        return new ConnectionContext("proj", "revenuecat", "conn", "sk-key", null, null,
                Map.of("projectId", projectId, "currency", "USD"), null);
    }

    /** A template whose charts are empty and whose overview answers {@code overviewBody}. */
    @SuppressWarnings("unchecked")
    private static RestTemplate restReturningOverview(Map<String, Object> overviewBody) {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("values", List.of())));
        when(restTemplate.exchange(pathContaining("/metrics/overview"), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(overviewBody));
        return restTemplate;
    }

    private static Map<String, Object> v2Metric(String id, Number value) {
        return Map.of("object", "overview_metric", "id", id, "name", id, "unit", "#", "period", "P0D",
                "value", value);
    }

    private static Map<String, Object> v2Overview(Number trials, Number subscriptions, Number mrr,
                                                  Number revenue, Number customers) {
        return Map.of("object", "overview_metrics", "currency", "USD", "metrics", List.of(
                v2Metric("active_trials", trials),
                v2Metric("active_subscriptions", subscriptions),
                v2Metric("mrr", mrr),
                v2Metric("revenue", revenue),
                v2Metric("new_customers", customers)));
    }

    private static Map<String, Object> flatOverview(int trials, int subscriptions, int mrrCents,
                                                    int revenueCents, int customers) {
        return Map.of(
                "active_trials", trials,
                "active_subscriptions", subscriptions,
                "mrr", mrrCents,
                "revenue_last_28_days", revenueCents,
                "new_customers_last_28_days", customers);
    }

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
        when(restTemplate.exchange(pathContaining("/metrics/overview"), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(overviewBody));

        // new_customers chart: {period, value}.
        Map<String, Object> customersBody = Map.of("values", List.of(
                Map.of("period", "2026-06-01", "value", 5),
                Map.of("period", "2026-06-02", "value", 7)));
        when(restTemplate.exchange(pathContaining("/charts/new_customers"), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(customersBody));

        // new_trials chart.
        when(restTemplate.exchange(pathContaining("/charts/new_trials"), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("values", List.of(
                        Map.of("period", "2026-06-01", "value", 2)))));

        // trial_conversion chart.
        when(restTemplate.exchange(pathContaining("/charts/trial_conversion"), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("values", List.of(
                        Map.of("period", "2026-06-01", "start_rate", 1.0, "conversion_rate", 0.45)))));

        // revenue chart: values in cents.
        when(restTemplate.exchange(pathContaining("/charts/revenue"), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class)))
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
        when(restTemplate.exchange(pathContaining("/metrics/overview"), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(Map.class)))
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

    // [auto] Both connectors warn when they return no data for the app's project (RevenueCat half)

    @Test
    @SuppressWarnings("unchecked")
    void allZeroFlatOverviewReturnsDegradedNamingTheProject() {
        RevenueCatConnector connector = new RevenueCatConnector(restReturningOverview(flatOverview(0, 0, 0, 0, 0)));

        ConnectorData result = connector.fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(result.errorMessage()).isEqualTo(NO_DATA_MESSAGE);
        // Whatever the connector has is still handed back so the page can render its (empty) charts.
        assertThat(result.data()).containsKeys("overview", "newCustomersSeries", "revenueSeries");
        assertThat((Map<String, Object>) result.data().get("overview")).containsEntry("activeSubscriptions", 0L);
    }

    @Test
    void allZeroV2OverviewReturnsDegradedNamingTheProject() {
        RevenueCatConnector connector = new RevenueCatConnector(
                restReturningOverview(v2Overview(0, 0, 0, 0, 0)));

        ConnectorData result = connector.fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(result.errorMessage()).isEqualTo(NO_DATA_MESSAGE);
    }

    @Test
    void emptyOrMissingOverviewMetricsReturnDegradedNamingTheProject() {
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of(),
                Map.of("object", "overview_metrics", "metrics", List.of()),
                Map.of("object", "overview_metrics", "metrics", List.of(
                        Map.of("object", "overview_metric", "id", "mrr")),
                        "currency", "USD"))) {
            RevenueCatConnector connector = new RevenueCatConnector(restReturningOverview(body));

            ConnectorData result = connector.fetchData(CREDS);

            assertThat(result.healthStatus()).as("body %s", body).isEqualTo(ConnectorHealth.DEGRADED);
            assertThat(result.errorMessage()).as("body %s", body).isEqualTo(NO_DATA_MESSAGE);
        }
    }

    @Test
    void emptyResponseBodyReturnsDegradedNamingTheProject() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(pathContaining("/metrics/overview"), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok().build());

        ConnectorData result = new RevenueCatConnector(restTemplate).fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(result.errorMessage()).isEqualTo(NO_DATA_MESSAGE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonZeroV2OverviewStaysHealthyAndIsReadInReportingCurrency() {
        RevenueCatConnector connector = new RevenueCatConnector(
                restReturningOverview(v2Overview(3, 120, 1234.56, 4321.5, 17)));

        ConnectorData result = connector.fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(result.errorMessage()).isNull();
        Map<String, Object> overview = (Map<String, Object>) result.data().get("overview");
        assertThat(overview.get("activeTrials")).isEqualTo(3L);
        assertThat(overview.get("activeSubscriptions")).isEqualTo(120L);
        assertThat(overview.get("mrr")).isEqualTo(1234.56);
        assertThat(overview.get("revenueLast28Days")).isEqualTo(4321.5);
        assertThat(overview.get("newCustomersLast28Days")).isEqualTo(17L);
    }

    @Test
    void oneNonZeroMetricIsEnoughToStayHealthy() {
        RevenueCatConnector connector = new RevenueCatConnector(
                restReturningOverview(flatOverview(0, 0, 0, 0, 1)));

        ConnectorData result = connector.fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
    }

    @Test
    void serverErrorOnOverviewKeepsTodaysHandlingAndIsNotReportedAsNoData() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(pathContaining("/metrics/overview"), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(Map.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, null, null));

        ConnectorData result = new RevenueCatConnector(restTemplate).fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(result.errorMessage()).startsWith("Failed to fetch data:");
        assertThat(result.errorMessage()).doesNotContain("returned no data");
    }

    @Test
    void rateLimitedOverviewKeepsTodaysHandlingAndIsNotReportedAsNoData() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(pathContaining("/metrics/overview"), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null));

        ConnectorData result = new RevenueCatConnector(restTemplate).fetchData(CREDS);

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(result.errorMessage()).contains("Rate limited");
    }

    @Test
    void projectIdIsUrlEncodedInEveryRequestPath() {
        RestTemplate restTemplate = restReturningOverview(flatOverview(1, 1, 100, 100, 1));
        RevenueCatConnector connector = new RevenueCatConnector(restTemplate);

        connector.fetchData(contextFor("proj/abc def"));

        ArgumentCaptor<URI> uris = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate, atLeastOnce()).exchange(uris.capture(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(Map.class));
        assertThat(uris.getAllValues()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(uris.getAllValues()).allSatisfy(uri -> {
            assertThat(uri.getRawPath()).startsWith("/v2/projects/proj%2Fabc%20def/");
            assertThat(uri.getHost()).isEqualTo("api.revenuecat.com");
        });
        assertThat(uris.getAllValues()).anySatisfy(uri ->
                assertThat(uri.getRawPath()).isEqualTo("/v2/projects/proj%2Fabc%20def/metrics/overview"));
        assertThat(uris.getAllValues()).anySatisfy(uri ->
                assertThat(uri.getRawQuery()).contains("currency=USD"));
    }

    @Test
    void plainProjectIdIsLeftAsIsInThePath() {
        RestTemplate restTemplate = restReturningOverview(flatOverview(1, 1, 100, 100, 1));

        new RevenueCatConnector(restTemplate).fetchData(CREDS);

        ArgumentCaptor<URI> uris = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate, atLeastOnce()).exchange(uris.capture(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(Map.class));
        assertThat(uris.getAllValues()).anySatisfy(uri ->
                assertThat(uri.getRawPath()).isEqualTo("/v2/projects/proj_abc/metrics/overview"));
    }

    // checkHealth

    @Test
    void checkHealthIsDegradedWhenTheOverviewIsAllZero() {
        RevenueCatConnector connector = new RevenueCatConnector(restReturningOverview(flatOverview(0, 0, 0, 0, 0)));

        assertThat(connector.checkHealth(CREDS)).isEqualTo(ConnectorHealth.DEGRADED);
    }

    @Test
    void checkHealthIsDegradedWhenTheV2OverviewHasNoMetrics() {
        RevenueCatConnector connector = new RevenueCatConnector(restReturningOverview(
                Map.of("object", "overview_metrics", "metrics", List.of())));

        assertThat(connector.checkHealth(CREDS)).isEqualTo(ConnectorHealth.DEGRADED);
    }

    @Test
    void checkHealthIsHealthyWhenTheOverviewHasData() {
        RevenueCatConnector connector = new RevenueCatConnector(
                restReturningOverview(v2Overview(0, 5, 49.99, 0, 2)));

        assertThat(connector.checkHealth(CREDS)).isEqualTo(ConnectorHealth.HEALTHY);
    }

    @Test
    void checkHealthIsDegradedOnAServerError() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(pathContaining("/metrics/overview"), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(Map.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null));

        assertThat(new RevenueCatConnector(restTemplate).checkHealth(CREDS)).isEqualTo(ConnectorHealth.DEGRADED);
    }

    @Test
    void checkHealthWithoutProjectIdIsSetupRequired() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ConnectionContext noProject =
                new ConnectionContext("proj", "revenuecat", "conn", "sk-key", null, null, Map.of(), null);

        assertThat(new RevenueCatConnector(restTemplate).checkHealth(noProject))
                .isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        verifyNoInteractions(restTemplate);
    }

    @Test
    void checkHealthEncodesTheProjectId() {
        RestTemplate restTemplate = restReturningOverview(flatOverview(1, 1, 100, 100, 1));

        new RevenueCatConnector(restTemplate).checkHealth(contextFor("a/b"));

        ArgumentCaptor<URI> uris = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uris.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        assertThat(uris.getValue().getRawPath()).isEqualTo("/v2/projects/a%2Fb/metrics/overview");
    }

    @Test
    void realRestTemplateSendsTheEncodedPathWithoutEncodingItAgain() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://api.revenuecat.com/v2/projects/proj%2Fabc%20def/metrics/overview"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"metrics\":[{\"id\":\"mrr\",\"value\":10}]}",
                        MediaType.APPLICATION_JSON));

        ConnectorHealth health = new RevenueCatConnector(restTemplate).checkHealth(contextFor("proj/abc def"));

        assertThat(health).isEqualTo(ConnectorHealth.HEALTHY);
        server.verify();
    }

    // The warning rides the same path as every other degraded FETCH result: IntegrationFetchService persists it on
    // the connection's data-cache row, which the data endpoint serves as errorMessage.
    @Test
    void noDataWarningIsPersistedOnTheConnectionDataCacheLikeOtherDegradedFetches() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ConnectorRegistry registry = mock(ConnectorRegistry.class);
            ConnectionService connectionService = mock(ConnectionService.class);
            ConnectionDataCacheRepository cacheRepository = mock(ConnectionDataCacheRepository.class);
            Connection conn = new Connection();
            conn.setId("conn");
            conn.setProjectId("proj");
            conn.setConnectorId("revenuecat");
            conn.setAuthType("API_KEY");
            RevenueCatConnector connector = new RevenueCatConnector(
                    restReturningOverview(flatOverview(0, 0, 0, 0, 0)));
            when(connectionService.getById("conn")).thenReturn(Optional.of(conn));
            when(registry.findFetch("revenuecat")).thenReturn(Optional.of(connector));
            when(connectionService.toContext(conn)).thenReturn(CREDS);
            when(cacheRepository.findByConnectionId("conn")).thenReturn(Optional.empty());
            IntegrationFetchService service = new IntegrationFetchService(registry, connectionService,
                    mock(OAuthFlowService.class), cacheRepository, new ObjectMapper(), executor);

            ConnectorData result = service.fetchData("conn", true);

            assertThat(result.errorMessage()).isEqualTo(NO_DATA_MESSAGE);
            ArgumentCaptor<ConnectionDataCache> saved = ArgumentCaptor.forClass(ConnectionDataCache.class);
            verify(cacheRepository).save(saved.capture());
            assertThat(saved.getValue().getHealthStatus()).isEqualTo("DEGRADED");
            assertThat(saved.getValue().getErrorMessage()).isEqualTo(NO_DATA_MESSAGE);
        } finally {
            executor.shutdownNow();
        }
    }
}
