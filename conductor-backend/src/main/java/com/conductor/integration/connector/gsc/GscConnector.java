package com.conductor.integration.connector.gsc;

import com.conductor.integration.*;
import com.conductor.integration.connector.gsc.model.*;
import java.util.List;
import java.util.Map;
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
     * picker. Unverified properties are dropped — they can't be queried.
     */
    public List<Map<String, String>> listSites(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<GscSitesResponse> response = restTemplate.exchange(
                "https://www.googleapis.com/webmasters/v3/sites",
                HttpMethod.GET, new HttpEntity<>(headers), GscSitesResponse.class);
        GscSitesResponse body = response.getBody();
        return body == null ? List.of() : body.siteEntryOrEmpty().stream()
                .filter(e -> !"siteUnverifiedUser".equals(e.permissionLevel()))
                .map(e -> Map.of("siteUrl", e.siteUrl(), "permissionLevel", e.permissionLevel()))
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
                List<GscAnalyticsRow> rows = queryRows(url, ctx.accessToken(),
                        new GscAnalyticsRequest(trendStart, end, List.of("date"), "web", 90, null));
                for (GscAnalyticsRow row : rows) {
                    trend.add(Map.of(
                            "date", row.firstKey(),
                            "clicks", row.clicks(),
                            "impressions", row.impressions(),
                            "ctr", row.ctr(),
                            "position", row.position()));
                }
            } catch (HttpClientErrorException e) {
                throw e;
            } catch (Exception e) {
                log.warn("GSC trend query failed: {}", e.getMessage());
            }

            // 2. Top queries (28d).
            List<Map<String, Object>> allQueries = new ArrayList<>();
            double totalQueryClicks = 0;
            try {
                List<GscAnalyticsRow> rows = queryRows(url, ctx.accessToken(),
                        new GscAnalyticsRequest(breakdownStart, end, List.of("query"), 25000));
                for (GscAnalyticsRow row : rows) {
                    totalQueryClicks += row.clicks();
                    allQueries.add(Map.of(
                            "query", row.firstKey(),
                            "clicks", row.clicks(),
                            "impressions", row.impressions(),
                            "ctr", row.ctr(),
                            "position", row.position()));
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
                    List<GscDimensionFilterGroup> filters = List.of(new GscDimensionFilterGroup("and",
                            List.of(new GscDimensionFilter("query", "contains", brandTerm))));
                    List<GscAnalyticsRow> rows = queryRows(url, ctx.accessToken(),
                            new GscAnalyticsRequest(breakdownStart, end, List.of("query"), null, 25000, filters));
                    double brandedClicks = rows.stream().mapToDouble(GscAnalyticsRow::clicks).sum();
                    brandedClickShare = brandedClicks / totalQueryClicks;
                } catch (Exception e) {
                    log.warn("GSC branded-share query failed: {}", e.getMessage());
                }
            }

            // 4. Top pages (28d).
            List<Map<String, Object>> topPages = new ArrayList<>();
            try {
                List<GscAnalyticsRow> rows = queryRows(url, ctx.accessToken(),
                        new GscAnalyticsRequest(breakdownStart, end, List.of("page"), 25000));
                rows.stream()
                        .sorted(Comparator.comparingDouble(r -> -r.clicks()))
                        .limit(25)
                        .forEach(row -> topPages.add(Map.of(
                                "page", row.firstKey(),
                                "clicks", row.clicks(),
                                "impressions", row.impressions())));
            } catch (Exception e) {
                log.warn("GSC page breakdown failed: {}", e.getMessage());
            }

            // 5. Countries (28d).
            List<Map<String, Object>> countries = new ArrayList<>();
            try {
                List<GscAnalyticsRow> rows = queryRows(url, ctx.accessToken(),
                        new GscAnalyticsRequest(breakdownStart, end, List.of("country"), 250));
                rows.stream()
                        .sorted(Comparator.comparingDouble(r -> -r.clicks()))
                        .limit(10)
                        .forEach(row -> countries.add(Map.of(
                                "country", row.firstKey(),
                                "clicks", row.clicks())));
            } catch (Exception e) {
                log.warn("GSC country breakdown failed: {}", e.getMessage());
            }

            // 6. Devices (28d).
            List<Map<String, Object>> devices = new ArrayList<>();
            try {
                List<GscAnalyticsRow> rows = queryRows(url, ctx.accessToken(),
                        new GscAnalyticsRequest(breakdownStart, end, List.of("device"), 10));
                for (GscAnalyticsRow row : rows) {
                    devices.add(Map.of("device", row.firstKey(), "clicks", row.clicks()));
                }
            } catch (Exception e) {
                log.warn("GSC device breakdown failed: {}", e.getMessage());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("siteUrl", siteUrl);
            data.put("trend", trend);
            data.put("topQueries", topQueries);
            data.put("brandedClickShare", brandedClickShare);
            data.put("topPages", topPages);
            data.put("countries", countries);
            data.put("devices", devices);
            return ConnectorData.healthy(data);

        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            // 404 = property not found for this account; 403 = not granted. Both are config problems.
            if (status == 404) {
                return ConnectorData.setupRequired(
                        "Property \"" + siteUrl + "\" wasn't found for your Google account. "
                                + "Check the exact format (sc-domain:example.com or https://example.com/).",
                        Map.of("siteUrl", siteUrl));
            }
            if (status == 403) {
                return ConnectorData.degraded(
                        "Your Google account doesn't have access to \"" + siteUrl + "\". "
                                + "Verify ownership in Search Console or re-connect with a different account.",
                        Map.of("siteUrl", siteUrl));
            }
            if (status == 401) {
                return ConnectorData.degraded("Check credentials", Map.of());
            }
            if (status == 429) {
                return ConnectorData.degraded("Rate limited — try again later", Map.of());
            }
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
            queryRows(queryUri(siteUrl), ctx.accessToken(), new GscAnalyticsRequest(
                    endDate.minusDays(BREAKDOWN_DAYS).format(iso),
                    endDate.format(iso),
                    List.of("date"), 1));
            return ConnectorHealth.HEALTHY;
        } catch (HttpClientErrorException e) {
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
        return URI.create("https://www.googleapis.com/webmasters/v3/sites/" + encoded + "/searchAnalytics/query");
    }

    private List<GscAnalyticsRow> queryRows(URI url, String accessToken, GscAnalyticsRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<GscAnalyticsResponse> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(request, headers), GscAnalyticsResponse.class);
        GscAnalyticsResponse body = response.getBody();
        return body != null ? body.rowsOrEmpty() : List.of();
    }

    private static String stringConfig(ConnectionContext ctx, String key) {
        Object v = ctx.configValue(key);
        return v != null ? v.toString() : null;
    }
}
