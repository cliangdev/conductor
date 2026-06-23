package com.conductor.integration.connector.gsc;

import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
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
import static org.mockito.Mockito.when;

class GscConnectorTest {

    private static final Map<String, Object> CONFIG =
            Map.of("siteUrl", "sc-domain:example.com", "brandTerm", "acme");

    private static ConnectionContext ctx(Map<String, Object> config) {
        return new ConnectionContext("proj", "gsc", "conn", "gsc-token", null, null, config, null);
    }

    @SuppressWarnings("unchecked")
    private static ResponseEntity<Map> rowsResponse(List<Map<String, Object>> rows) {
        return ResponseEntity.ok(Map.of("rows", rows));
    }

    @Test
    void oauthScopesIncludesWebmastersReadonly() {
        GscConnector connector = new GscConnector(mock(RestTemplate.class));
        assertThat(connector.oauthScopes())
                .contains("https://www.googleapis.com/auth/webmasters.readonly");
    }

    @Test
    void missingSiteUrlReturnsSetupRequired() {
        GscConnector connector = new GscConnector(mock(RestTemplate.class));

        ConnectorData result = connector.fetchData(ctx(Map.of()));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        assertThat(result.data().get("oauthConnected")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsRowKeysToDimensionFieldsAndComputesBrandedShare() {
        RestTemplate restTemplate = mock(RestTemplate.class);

        // 1. trend (date) — one row.
        ResponseEntity<Map> trend = rowsResponse(List.of(Map.of(
                "keys", List.of("2026-06-01"),
                "clicks", 100.0, "impressions", 2000.0, "ctr", 0.05, "position", 3.2)));

        // 2. top queries (query) — total clicks 100 + 300 = 400.
        ResponseEntity<Map> queries = rowsResponse(List.of(
                Map.of("keys", List.of("acme analytics"), "clicks", 100.0,
                        "impressions", 1000.0, "ctr", 0.1, "position", 2.0),
                Map.of("keys", List.of("session replay"), "clicks", 300.0,
                        "impressions", 5000.0, "ctr", 0.06, "position", 6.0)));

        // 3. branded share (filtered query) — branded clicks 100 → share 100/400 = 0.25.
        ResponseEntity<Map> branded = rowsResponse(List.of(
                Map.of("keys", List.of("acme analytics"), "clicks", 100.0,
                        "impressions", 1000.0, "ctr", 0.1, "position", 2.0)));

        // 4. pages, 5. countries, 6. devices.
        ResponseEntity<Map> pages = rowsResponse(List.of(Map.of(
                "keys", List.of("https://example.com/"), "clicks", 250.0, "impressions", 4000.0)));
        ResponseEntity<Map> countries = rowsResponse(List.of(Map.of(
                "keys", List.of("usa"), "clicks", 320.0)));
        ResponseEntity<Map> devices = rowsResponse(List.of(Map.of(
                "keys", List.of("DESKTOP"), "clicks", 280.0)));

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(trend, queries, branded, pages, countries, devices);

        GscConnector connector = new GscConnector(restTemplate);
        ConnectorData result = connector.fetchData(ctx(CONFIG));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);

        List<Map<String, Object>> trendOut = (List<Map<String, Object>>) result.data().get("trend");
        assertThat(trendOut).hasSize(1);
        assertThat(trendOut.get(0).get("date")).isEqualTo("2026-06-01");
        assertThat(trendOut.get(0).get("clicks")).isEqualTo(100.0);

        List<Map<String, Object>> topQueries = (List<Map<String, Object>>) result.data().get("topQueries");
        // Sorted by clicks desc: session replay (300) before acme analytics (100).
        assertThat(topQueries.get(0).get("query")).isEqualTo("session replay");
        assertThat(topQueries.get(0).get("impressions")).isEqualTo(5000.0);

        assertThat((double) result.data().get("brandedClickShare")).isEqualTo(0.25);

        List<Map<String, Object>> topPages = (List<Map<String, Object>>) result.data().get("topPages");
        assertThat(topPages.get(0).get("page")).isEqualTo("https://example.com/");

        List<Map<String, Object>> countriesOut = (List<Map<String, Object>>) result.data().get("countries");
        assertThat(countriesOut.get(0).get("country")).isEqualTo("usa");

        List<Map<String, Object>> devicesOut = (List<Map<String, Object>>) result.data().get("devices");
        assertThat(devicesOut.get(0).get("device")).isEqualTo("DESKTOP");
    }

    @Test
    void notFoundPropertyRoutesBackToSetup() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        GscConnector connector = new GscConnector(restTemplate);
        ConnectorData result = connector.fetchData(ctx(CONFIG));

        // A 404 means the configured property isn't accessible — a setup problem, not a fetch fault.
        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        assertThat(result.data().get("oauthConnected")).isEqualTo(true);
        assertThat(result.errorMessage()).contains("sc-domain:example.com");
    }

    @Test
    void missingSiteUrlInCheckHealthReturnsSetupRequired() {
        GscConnector connector = new GscConnector(mock(RestTemplate.class));
        assertThat(connector.checkHealth(ctx(Map.of()))).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listSitesReturnsVerifiedPropertiesOnly() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ResponseEntity<Map> sites = ResponseEntity.ok(Map.of("siteEntry", List.of(
                Map.of("siteUrl", "sc-domain:example.com", "permissionLevel", "siteOwner"),
                Map.of("siteUrl", "https://blog.example.com/", "permissionLevel", "siteUnverifiedUser"))));
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(sites);

        GscConnector connector = new GscConnector(restTemplate);
        List<Map<String, String>> result = connector.listSites("gsc-token");

        // The unverified property is dropped — it can't be queried.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("siteUrl")).isEqualTo("sc-domain:example.com");
        assertThat(result.get(0).get("permissionLevel")).isEqualTo("siteOwner");
    }
}
