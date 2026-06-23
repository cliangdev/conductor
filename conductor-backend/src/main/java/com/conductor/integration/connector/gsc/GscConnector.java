package com.conductor.integration.connector.gsc;

import com.conductor.integration.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
@Profile("!local")
public class GscConnector implements FetchConnector, OAuth2Connector {

    private static final Logger log = LoggerFactory.getLogger(GscConnector.class);

    /** GSC data lags ~2-3 days; query up to today minus this. */
    private static final int LAG_DAYS = 3;
    private static final int TREND_DAYS = 90;
    private static final int BREAKDOWN_DAYS = 28;

    private final RestTemplate restTemplate;

    public GscConnector() {
        this.restTemplate = ConnectorHttp.restTemplate();
    }

    GscConnector(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String getId() { return "gsc"; }

    @Override
    public List<String> oauthScopes() {
        return List.of("https://www.googleapis.com/auth/webmasters.readonly");
    }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("gsc", "Google Search Console", ConnectorCategory.MARKETING,
                "Organic search acquisition from Google Search Console", "GSC");
    }

    @Override
    public ConnectorSpec getSpec() {
        // OAuth captures the token; siteUrl + brandTerm are populated post-auth via a config PATCH.
        return ConnectorSpec.oauth2(true, List.of(
            ConnectorConfigField.userInput("siteUrl", "Search Console property",
                "Verified GSC property, e.g. sc-domain:example.com or https://example.com/",
                FieldType.STRING, true),
            ConnectorConfigField.userInput("brandTerm", "Brand term",
                "Your brand name, for the branded vs non-branded split",
                FieldType.STRING, false)
        ));
    }

    /**
     * Lists the Search Console properties the authorized account can read, for the post-OAuth property
     * picker. Unverified properties are dropped — they can't be queried. Mirrors GcpBillingConnector's
     * project/dataset pickers.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> listSites(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "https://www.googleapis.com/webmasters/v3/sites",
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        List<Map<String, Object>> entries = response.getBody() != null
                ? (List<Map<String, Object>>) response.getBody().getOrDefault("siteEntry", List.of())
                : List.of();
        return entries.stream()
                .filter(e -> !"siteUnverifiedUser".equals(String.valueOf(e.get("permissionLevel"))))
                .map(e -> Map.of(
                        "siteUrl", String.valueOf(e.get("siteUrl")),
                        "permissionLevel", String.valueOf(e.get("permissionLevel"))))
                .toList();
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofHours(6); }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        String siteUrl = stringConfig(ctx, "siteUrl");
        if (siteUrl == null || siteUrl.isBlank()) {
            return ConnectorData.setupRequired("Configure your Search Console property",
                    Map.of("oauthConnected", true));
        }
        String brandTerm = stringConfig(ctx, "brandTerm");

        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
        LocalDate endDate = LocalDate.now().minusDays(LAG_DAYS);
        String end = endDate.format(iso);
        String trendStart = endDate.minusDays(TREND_DAYS).format(iso);
        String breakdownStart = endDate.minusDays(BREAKDOWN_DAYS).format(iso);

        try {
            URI url = queryUri(siteUrl);

            // 1. Daily trend (~90d).
            List<Map<String, Object>> trend = new ArrayList<>();
            try {
                List<Map<String, Object>> rows = queryRows(url, ctx.accessToken(), Map.of(
                        "startDate", trendStart, "endDate", end,
                        "dimensions", List.of("date"), "type", "web", "rowLimit", 90));
                for (Map<String, Object> row : rows) {
                    List<?> keys = (List<?>) row.get("keys");
                    trend.add(Map.of(
                            "date", keys != null && !keys.isEmpty() ? String.valueOf(keys.get(0)) : "",
                            "clicks", num(row.get("clicks")),
                            "impressions", num(row.get("impressions")),
                            "ctr", num(row.get("ctr")),
                            "position", num(row.get("position"))));
                }
            } catch (HttpClientErrorException e) {
                throw e; // fatal auth/permission errors surface below
            } catch (Exception e) {
                log.warn("GSC trend query failed: {}", e.getMessage());
            }

            // 2. Top queries (28d).
            List<Map<String, Object>> allQueries = new ArrayList<>();
            double totalQueryClicks = 0;
            try {
                List<Map<String, Object>> rows = queryRows(url, ctx.accessToken(), Map.of(
                        "startDate", breakdownStart, "endDate", end,
                        "dimensions", List.of("query"), "rowLimit", 25000));
                for (Map<String, Object> row : rows) {
                    List<?> keys = (List<?>) row.get("keys");
                    double clicks = num(row.get("clicks"));
                    totalQueryClicks += clicks;
                    allQueries.add(Map.of(
                            "query", keys != null && !keys.isEmpty() ? String.valueOf(keys.get(0)) : "",
                            "clicks", clicks,
                            "impressions", num(row.get("impressions")),
                            "ctr", num(row.get("ctr")),
                            "position", num(row.get("position"))));
                }
            } catch (Exception e) {
                log.warn("GSC query breakdown failed: {}", e.getMessage());
            }
            List<Map<String, Object>> topQueries = allQueries.stream()
                    .sorted(Comparator.comparingDouble(q -> -((Number) q.get("clicks")).doubleValue()))
                    .limit(50)
                    .toList();

            // 3. Branded share (28d) — only if brandTerm present.
            double brandedClickShare = 0;
            if (brandTerm != null && !brandTerm.isBlank() && totalQueryClicks > 0) {
                try {
                    List<Map<String, Object>> rows = queryRows(url, ctx.accessToken(), Map.of(
                            "startDate", breakdownStart, "endDate", end,
                            "dimensions", List.of("query"), "rowLimit", 25000,
                            "dimensionFilterGroups", List.of(Map.of(
                                    "groupType", "and",
                                    "filters", List.of(Map.of(
                                            "dimension", "query",
                                            "operator", "contains",
                                            "expression", brandTerm))))));
                    double brandedClicks = 0;
                    for (Map<String, Object> row : rows) {
                        brandedClicks += num(row.get("clicks"));
                    }
                    brandedClickShare = brandedClicks / totalQueryClicks;
                } catch (Exception e) {
                    log.warn("GSC branded-share query failed: {}", e.getMessage());
                }
            }

            // 4. Top pages (28d).
            List<Map<String, Object>> topPages = new ArrayList<>();
            try {
                List<Map<String, Object>> rows = queryRows(url, ctx.accessToken(), Map.of(
                        "startDate", breakdownStart, "endDate", end,
                        "dimensions", List.of("page"), "rowLimit", 25000));
                rows.stream()
                        .sorted(Comparator.comparingDouble(r -> -num(r.get("clicks"))))
                        .limit(25)
                        .forEach(row -> {
                            List<?> keys = (List<?>) row.get("keys");
                            topPages.add(Map.of(
                                    "page", keys != null && !keys.isEmpty() ? String.valueOf(keys.get(0)) : "",
                                    "clicks", num(row.get("clicks")),
                                    "impressions", num(row.get("impressions"))));
                        });
            } catch (Exception e) {
                log.warn("GSC page breakdown failed: {}", e.getMessage());
            }

            // 5. Countries (28d).
            List<Map<String, Object>> countries = new ArrayList<>();
            try {
                List<Map<String, Object>> rows = queryRows(url, ctx.accessToken(), Map.of(
                        "startDate", breakdownStart, "endDate", end,
                        "dimensions", List.of("country"), "rowLimit", 250));
                rows.stream()
                        .sorted(Comparator.comparingDouble(r -> -num(r.get("clicks"))))
                        .limit(10)
                        .forEach(row -> {
                            List<?> keys = (List<?>) row.get("keys");
                            countries.add(Map.of(
                                    "country", keys != null && !keys.isEmpty() ? String.valueOf(keys.get(0)) : "",
                                    "clicks", num(row.get("clicks"))));
                        });
            } catch (Exception e) {
                log.warn("GSC country breakdown failed: {}", e.getMessage());
            }

            // 6. Devices (28d).
            List<Map<String, Object>> devices = new ArrayList<>();
            try {
                List<Map<String, Object>> rows = queryRows(url, ctx.accessToken(), Map.of(
                        "startDate", breakdownStart, "endDate", end,
                        "dimensions", List.of("device"), "rowLimit", 10));
                for (Map<String, Object> row : rows) {
                    List<?> keys = (List<?>) row.get("keys");
                    devices.add(Map.of(
                            "device", keys != null && !keys.isEmpty() ? String.valueOf(keys.get(0)) : "",
                            "clicks", num(row.get("clicks"))));
                }
            } catch (Exception e) {
                log.warn("GSC device breakdown failed: {}", e.getMessage());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("trend", trend);
            data.put("topQueries", topQueries);
            data.put("brandedClickShare", brandedClickShare);
            data.put("topPages", topPages);
            data.put("countries", countries);
            data.put("devices", devices);
            return ConnectorData.healthy(data);

        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            // 404 = the property string isn't a property this account can access; 403 = not granted.
            // Both are configuration problems, so route the user back to the property picker.
            if (status == 404) {
                return ConnectorData.setupRequired(
                        "Couldn't find \"" + siteUrl + "\" for your Google account. Pick a verified "
                                + "property from the list, or check the exact format "
                                + "(sc-domain:example.com or https://example.com/).",
                        Map.of("oauthConnected", true));
            }
            if (status == 403) {
                return ConnectorData.setupRequired(
                        "Your Google account doesn't have access to this Search Console property. "
                                + "Pick one you've been granted, or verify it in Search Console.",
                        Map.of("oauthConnected", true));
            }
            if (status == 401) {
                return ConnectorData.degraded("Check credentials", Map.of());
            }
            if (status == 429) {
                return ConnectorData.degraded("Rate limited — try again later", Map.of());
            }
            // Log Google's response body too — the bare status code wasn't enough to diagnose past
            // issues (e.g. a double-encoded property URL surfaced only as a generic 404).
            log.warn("GSC API error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ConnectorData.degraded("Search Console API error: " + e.getStatusCode(), Map.of());
        } catch (Exception e) {
            log.warn("GSC fetch failed: {}", e.getMessage());
            return ConnectorData.degraded("Failed to fetch data: " + e.getMessage(), Map.of());
        }
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        String siteUrl = stringConfig(ctx, "siteUrl");
        if (siteUrl == null || siteUrl.isBlank()) {
            return ConnectorHealth.SETUP_REQUIRED;
        }
        try {
            DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
            LocalDate endDate = LocalDate.now().minusDays(LAG_DAYS);
            queryRows(queryUri(siteUrl), ctx.accessToken(), Map.of(
                    "startDate", endDate.minusDays(BREAKDOWN_DAYS).format(iso),
                    "endDate", endDate.format(iso),
                    "dimensions", List.of("date"), "rowLimit", 1));
            return ConnectorHealth.HEALTHY;
        } catch (HttpClientErrorException e) {
            // 403 = property not granted; 404 = property not found for this account. Both are setup
            // problems (wrong/inaccessible property), not faults.
            int status = e.getStatusCode().value();
            if (status == 403 || status == 404) {
                return ConnectorHealth.SETUP_REQUIRED;
            }
            return ConnectorHealth.DEGRADED;
        } catch (Exception e) {
            return ConnectorHealth.DEGRADED;
        }
    }

    /**
     * Builds the searchAnalytics URI with the property pre-encoded as a single path segment, and
     * returns a {@link URI} (not a String). Passing a URI to RestTemplate bypasses its String-URL
     * handler, which would otherwise re-encode the '%' and turn {@code sc-domain%3A…} into
     * {@code sc-domain%253A…} — Google then 404s the (now non-existent) property.
     */
    private URI queryUri(String siteUrl) {
        String encoded = URLEncoder.encode(siteUrl, StandardCharsets.UTF_8);
        return URI.create("https://searchconsole.googleapis.com/v1/sites/" + encoded + "/searchAnalytics/query");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queryRows(URI url, String accessToken, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        Map<String, Object> respBody = response.getBody();
        if (respBody == null) return List.of();
        Object rows = respBody.get("rows");
        return rows instanceof List ? (List<Map<String, Object>>) rows : List.of();
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static String stringConfig(ConnectionContext ctx, String key) {
        Object v = ctx.configValue(key);
        return v != null ? v.toString() : null;
    }
}
