package com.conductor.integration.connector.applesearchads;

import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppleSearchAdsConnectorTest {

    /** Full config minus the campaignId, so keyword/search-term reports are skipped. */
    private static ConnectionContext ctx(Map<String, Object> config) {
        return new ConnectionContext(
                "proj", "apple-search-ads", "conn",
                "-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----", // .p8 secret in accessToken
                null, null, config, null);
    }

    private static Map<String, Object> fullConfig() {
        Map<String, Object> c = new HashMap<>();
        c.put("clientId", "CLIENT.abc");
        c.put("teamId", "TEAM.xyz");
        c.put("keyId", "KEY123");
        c.put("orgId", "999");
        return c;
    }

    /** One campaign with two daily buckets; money fields are {amount, currency} objects. */
    private static Map<String, Object> campaignReportBody() {
        Map<String, Object> day1 = Map.of(
                "date", "2026-06-01",
                "newDownloads", 30,
                "installs", 35,
                "localSpend", Map.of("amount", "120.50", "currency", "USD"),
                "avgCPA", Map.of("amount", "4.00", "currency", "USD"),
                "avgCPT", Map.of("amount", "1.50", "currency", "USD"),
                "conversionRate", 42.0);
        Map<String, Object> day2 = Map.of(
                "date", "2026-06-02",
                "newDownloads", 50,
                "installs", 55,
                "localSpend", Map.of("amount", "200.00", "currency", "USD"),
                "avgCPA", Map.of("amount", "4.00", "currency", "USD"),
                "avgCPT", Map.of("amount", "1.60", "currency", "USD"),
                "conversionRate", 44.0);
        Map<String, Object> row = Map.of(
                "metadata", Map.of("campaignId", 1, "campaignName", "Brand"),
                "granularity", List.of(day1, day2));
        return Map.of("data", Map.of("reportingDataResponse", Map.of("row", List.of(row))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthyFetch_extractsMoneyAndAggregatesNewDownloads() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        AppleAdsTokenService tokenService = mock(AppleAdsTokenService.class);
        when(tokenService.accessToken(any(), any())).thenReturn("bearer-token");
        when(restTemplate.exchange(contains("/reports/campaigns"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(campaignReportBody()));

        AppleSearchAdsConnector connector = new AppleSearchAdsConnector(restTemplate, tokenService);

        ConnectorData result = connector.fetchData(ctx(fullConfig()));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);

        List<Map<String, Object>> installs = (List<Map<String, Object>>) result.data().get("installsSeries");
        assertThat(installs).hasSize(2);
        assertThat(((Number) installs.get(0).get("newDownloads")).doubleValue()).isEqualTo(30.0);
        assertThat(((Number) installs.get(1).get("newDownloads")).doubleValue()).isEqualTo(50.0);

        List<Map<String, Object>> spend = (List<Map<String, Object>>) result.data().get("spendSeries");
        // localSpend {amount:"120.50"} extracted to a double.
        assertThat(((Number) spend.get(0).get("localSpend")).doubleValue()).isEqualTo(120.50);
        assertThat(((Number) spend.get(1).get("localSpend")).doubleValue()).isEqualTo(200.00);

        List<Map<String, Object>> eff = (List<Map<String, Object>>) result.data().get("effSeries");
        assertThat(((Number) eff.get(0).get("avgCPA")).doubleValue()).isEqualTo(4.00);

        // No campaignId → keyword/search-term reports left empty.
        assertThat((List<?>) result.data().get("topKeywords")).isEmpty();
        assertThat((List<?>) result.data().get("topSearchTerms")).isEmpty();
    }

    @Test
    void missingCredentials_returnsSetupRequired() {
        AppleSearchAdsConnector connector = new AppleSearchAdsConnector(
                mock(RestTemplate.class), mock(AppleAdsTokenService.class));

        // Missing teamId/keyId/orgId.
        ConnectorData result = connector.fetchData(ctx(Map.of("clientId", "CLIENT.abc")));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }
}
