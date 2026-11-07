package com.conductor.integration.connector;

import com.conductor.integration.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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
            ResponseEntity<Map> overviewResp = restTemplate.exchange(
                    projectUri(projectId, Map.of("currency", currency), "metrics", "overview"),
                    HttpMethod.GET, request, Map.class);
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

            if (!overviewHasData(overview)) {
                return ConnectorData.degraded(noDataMessage(projectId), data);
            }
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

    /**
     * Reads the overview from either of its shapes. The v2 API answers {@code {"metrics":[{"id","value"}]}} with
     * money already in the reporting currency; the flat shape carries named fields with money in cents.
     * A metric the response does not carry reads as 0 here, which {@link #overviewHasData} treats as "no data".
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseOverview(Map body) {
        Map<String, Object> overview = new HashMap<>();
        if (body == null) return overview;

        Map<String, Object> byId = metricsById(body);
        if (!byId.isEmpty()) {
            overview.put("activeTrials", longOrZero(byId.get("active_trials")));
            overview.put("activeSubscriptions", longOrZero(byId.get("active_subscriptions")));
            overview.put("mrr", doubleOrZero(byId.get("mrr")));
            overview.put("revenueLast28Days", doubleOrZero(byId.get("revenue")));
            overview.put("newCustomersLast28Days", longOrZero(byId.get("new_customers")));
            return overview;
        }

        overview.put("activeTrials", longOrZero(body.get("active_trials")));
        overview.put("activeSubscriptions", longOrZero(body.get("active_subscriptions")));
        overview.put("mrr", centsToDollars(body.get("mrr")));
        overview.put("revenueLast28Days", centsToDollars(body.get("revenue_last_28_days")));
        overview.put("newCustomersLast28Days", longOrZero(body.get("new_customers_last_28_days")));
        return overview;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metricsById(Map body) {
        Map<String, Object> byId = new HashMap<>();
        if (!(body.get("metrics") instanceof List<?> metrics)) return byId;
        for (Object item : metrics) {
            if (item instanceof Map<?, ?> metric && metric.get("id") instanceof String id
                    && metric.get("value") instanceof Number value) {
                byId.put(id, value);
            }
        }
        return byId;
    }

    /** True when at least one overview metric is a non-zero number; empty, missing and zero all count as no data. */
    private static boolean overviewHasData(Map<String, Object> overview) {
        return overview.values().stream()
                .anyMatch(v -> v instanceof Number n && n.doubleValue() != 0.0);
    }

    private static String noDataMessage(Object projectId) {
        return "RevenueCat returned no data for project " + projectId
                + ". Check the project ID, or disconnect it if this app does not bill through RevenueCat.";
    }

    /** {@code /v2/projects/{projectId}/{tail...}} with the project id encoded as one path segment. */
    private static URI projectUri(String projectId, Map<String, String> query, String... tail) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(BASE_URL)
                .pathSegment("v2", "projects", projectId)
                .pathSegment(tail);
        query.forEach(builder::queryParam);
        return builder.build().encode().toUri();
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
            URI url = projectUri(projectId,
                    Map.of("resolution", resolution, "start_time", startTime, "end_time", endTime),
                    "charts", chart);
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
            URI url = projectUri(projectId,
                    Map.of("resolution", "week", "start_time", startTime, "end_time", endTime),
                    "charts", "trial_conversion");
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
    @SuppressWarnings("unchecked")
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        try {
            Object projectIdObj = ctx.configValue("projectId");
            if (projectIdObj == null) return ConnectorHealth.SETUP_REQUIRED;

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(ctx.accessToken());
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<Map> overviewResp = restTemplate.exchange(
                    projectUri(projectIdObj.toString(), Map.of(), "metrics", "overview"),
                    HttpMethod.GET, request, Map.class);
            return overviewHasData(parseOverview(overviewResp.getBody()))
                    ? ConnectorHealth.HEALTHY : ConnectorHealth.DEGRADED;
        } catch (Exception e) {
            return ConnectorHealth.DEGRADED;
        }
    }
}
