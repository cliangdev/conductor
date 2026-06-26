package com.conductor.integration.connector;

import com.conductor.integration.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
@Profile("!local")
public class RevenueCatConnector implements FetchConnector {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatConnector.class);
    private static final String BASE_URL = "https://api.revenuecat.com";

    private final RestTemplate restTemplate;

    public RevenueCatConnector() {
        this.restTemplate = ConnectorHttp.restTemplate();
    }

    RevenueCatConnector(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String getId() { return "revenuecat"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("revenuecat", "RevenueCat", ConnectorCategory.FINANCE,
                "Subscription revenue, trials, and conversion from RevenueCat", "RC");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(true, List.of(
            ConnectorConfigField.userInput("apiKey", "API Key",
                "RevenueCat → Project Settings → API Keys → V2 secret key (sk_...)",
                FieldType.SECRET, true),
            ConnectorConfigField.userInput("projectId", "Project ID",
                "RevenueCat project ID (used in /v2/projects/{id})", FieldType.STRING, true),
            ConnectorConfigField.userInput("currency", "Currency",
                "Reporting currency (default USD)", FieldType.STRING, false)
        ));
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofMinutes(30); }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        try {
            String apiKey = ctx.accessToken();
            Object projectIdObj = ctx.configValue("projectId");
            if (projectIdObj == null) {
                return ConnectorData.setupRequired("RevenueCat Project ID not configured");
            }
            String projectId = projectIdObj.toString();

            Object currencyObj = ctx.configValue("currency");
            String currency = currencyObj != null && !currencyObj.toString().isBlank()
                    ? currencyObj.toString() : "USD";

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> request = new HttpEntity<>(headers);

            // 1. Overview snapshot — failure here is fatal (surfaces credential problems).
            String overviewUrl = BASE_URL + "/v2/projects/" + projectId
                    + "/metrics/overview?currency=" + currency;
            ResponseEntity<Map> overviewResp = restTemplate.exchange(
                    overviewUrl, HttpMethod.GET, request, Map.class);
            Map<String, Object> overview = parseOverview(overviewResp.getBody());

            // Charts are best-effort: a single failing series degrades to empty, not a whole-fetch failure.
            Instant now = Instant.now();
            Instant thirtyDaysAgo = now.minus(Duration.ofDays(30));
            String startTime = toIso(thirtyDaysAgo);
            String endTime = toIso(now);

            List<Map<String, Object>> newCustomers = fetchValueSeries(
                    request, projectId, "new_customers", "day", startTime, endTime, false);
            List<Map<String, Object>> newTrials = fetchValueSeries(
                    request, projectId, "new_trials", "day", startTime, endTime, false);
            List<Map<String, Object>> trialConversion = fetchTrialConversion(
                    request, projectId, startTime, endTime);
            List<Map<String, Object>> revenue = fetchValueSeries(
                    request, projectId, "revenue", "day", startTime, endTime, true);

            Map<String, Object> data = new HashMap<>();
            data.put("overview", overview);
            data.put("newCustomersSeries", newCustomers);
            data.put("newTrialsSeries", newTrials);
            data.put("trialConversion", trialConversion);
            data.put("revenueSeries", revenue);

            return ConnectorData.healthy(data);

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("RevenueCat rate limited for project");
                return ConnectorData.degraded("Rate limited — try again later", Map.of());
            }
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                return ConnectorData.degraded("Check credentials", Map.of());
            }
            log.warn("RevenueCat API error: {}", e.getStatusCode());
            return ConnectorData.degraded("RevenueCat API error: " + e.getStatusCode(), Map.of());
        } catch (Exception e) {
            log.warn("RevenueCat fetch failed: {}", e.getMessage());
            return ConnectorData.degraded("Failed to fetch data: " + e.getMessage(), Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseOverview(Map body) {
        Map<String, Object> overview = new HashMap<>();
        if (body == null) return overview;

        overview.put("activeTrials", longOrZero(body.get("active_trials")));
        overview.put("activeSubscriptions", longOrZero(body.get("active_subscriptions")));
        overview.put("mrr", centsToDollars(body.get("mrr")));
        overview.put("revenueLast28Days", centsToDollars(body.get("revenue_last_28_days")));
        overview.put("newCustomersLast28Days", longOrZero(body.get("new_customers_last_28_days")));
        return overview;
    }

    /**
     * Fetch a chart whose values are {period, value}. When {@code cents} is true the value is
     * money in cents and is converted to dollars. Maps to {@code [{date, value}]}.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchValueSeries(HttpEntity<Void> request, String projectId,
                                                       String chart, String resolution,
                                                       String startTime, String endTime, boolean cents) {
        try {
            String url = BASE_URL + "/v2/projects/" + projectId + "/charts/" + chart
                    + "?resolution=" + resolution + "&start_time=" + startTime + "&end_time=" + endTime;
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, request, Map.class);
            Map body = resp.getBody();
            if (body == null) return List.of();
            List<Map<String, Object>> values = (List<Map<String, Object>>) body.get("values");
            if (values == null) return List.of();

            List<Map<String, Object>> series = new ArrayList<>();
            for (Map<String, Object> v : values) {
                Object period = v.get("period");
                Object raw = v.get("value");
                double value = cents ? centsToDollars(raw) : doubleOrZero(raw);
                series.add(Map.of("date", period != null ? period : "", "value", value));
            }
            return series;
        } catch (Exception e) {
            log.warn("RevenueCat chart {} failed: {}", chart, e.getMessage());
            return List.of();
        }
    }

    /** Trial-conversion chart → {@code [{period, startRate, conversionRate}]}, tolerant of missing fields. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchTrialConversion(HttpEntity<Void> request, String projectId,
                                                           String startTime, String endTime) {
        try {
            String url = BASE_URL + "/v2/projects/" + projectId + "/charts/trial_conversion"
                    + "?resolution=week&start_time=" + startTime + "&end_time=" + endTime;
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, request, Map.class);
            Map body = resp.getBody();
            if (body == null) return List.of();
            List<Map<String, Object>> values = (List<Map<String, Object>>) body.get("values");
            if (values == null) return List.of();

            List<Map<String, Object>> series = new ArrayList<>();
            for (Map<String, Object> v : values) {
                Map<String, Object> point = new HashMap<>();
                point.put("period", v.getOrDefault("period", ""));
                point.put("startRate", doubleOrZero(v.get("start_rate")));
                point.put("conversionRate", doubleOrZero(v.get("conversion_rate")));
                series.add(point);
            }
            return series;
        } catch (Exception e) {
            log.warn("RevenueCat trial_conversion failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String toIso(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static long longOrZero(Object n) {
        return n instanceof Number num ? num.longValue() : 0L;
    }

    private static double doubleOrZero(Object n) {
        return n instanceof Number num ? num.doubleValue() : 0.0;
    }

    private static double centsToDollars(Object n) {
        return n instanceof Number num ? num.doubleValue() / 100.0 : 0.0;
    }

    @Override
    public IntegrationToolSpec getToolSpec() {
        return new IntegrationToolSpec(
            "Subscription revenue, trials, and conversion metrics from RevenueCat",
            List.of(
                new ToolOperation("overview", "Snapshot: MRR, active subscriptions, active trials, revenue last 28 days, new customers last 28 days",
                    Map.of(), "{overview:{mrr,activeSubscriptions,activeTrials,revenueLast28Days,newCustomersLast28Days}}"),
                new ToolOperation("revenue_series", "Daily revenue series (30d)",
                    Map.of(), "{revenueSeries:[{date,value}]}"),
                new ToolOperation("trial_conversion", "Weekly trial-to-paid conversion series (30d)",
                    Map.of(), "{trialConversion:[{period,startRate,conversionRate}]}")
            )
        );
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        try {
            Object projectIdObj = ctx.configValue("projectId");
            if (projectIdObj == null) return ConnectorHealth.SETUP_REQUIRED;

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(ctx.accessToken());
            HttpEntity<Void> request = new HttpEntity<>(headers);

            String url = BASE_URL + "/v2/projects/" + projectIdObj + "/metrics/overview";
            restTemplate.exchange(url, HttpMethod.GET, request, Map.class);
            return ConnectorHealth.HEALTHY;
        } catch (Exception e) {
            return ConnectorHealth.DEGRADED;
        }
    }
}
