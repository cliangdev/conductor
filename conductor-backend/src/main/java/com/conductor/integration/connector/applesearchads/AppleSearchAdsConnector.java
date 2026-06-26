package com.conductor.integration.connector.applesearchads;

import com.conductor.integration.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Apple Search Ads (FETCH connector). Surfaces paid user-acquisition metrics — new downloads, spend,
 * CPA/CPT, conversion — plus top keywords and search terms for a chosen campaign.
 *
 * <p>Apple's auth is a custom ES256-JWT client-credentials exchange; that logic is fully isolated in
 * {@link AppleAdsTokenService}, which this connector instantiates directly. To the rest of the framework
 * this is an ordinary {@code API_KEY} connector: the .p8 private key is stored as the encrypted secret
 * ({@code ctx.accessToken()}), and the other identifiers live in plaintext config.
 */
@Component
@Profile("!local")
public class AppleSearchAdsConnector implements FetchConnector {

    private static final Logger log = LoggerFactory.getLogger(AppleSearchAdsConnector.class);

    private static final String API_BASE = "https://api.searchads.apple.com/api/v5";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE; // yyyy-MM-dd
    private static final int WINDOW_DAYS = 30;

    private final RestTemplate restTemplate;
    private final AppleAdsTokenService tokenService;

    public AppleSearchAdsConnector() {
        this.restTemplate = ConnectorHttp.restTemplate();
        this.tokenService = new AppleAdsTokenService(this.restTemplate);
    }

    /** Test seam: inject a mock RestTemplate and a stubbed token service. */
    AppleSearchAdsConnector(RestTemplate restTemplate, AppleAdsTokenService tokenService) {
        this.restTemplate = restTemplate;
        this.tokenService = tokenService;
    }

    @Override
    public String getId() { return "apple-search-ads"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("apple-search-ads", "Apple Search Ads", ConnectorCategory.MARKETING,
                "Paid user acquisition from Apple Search Ads", "ASA");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(true, List.of(
            ConnectorConfigField.userInput("clientId", "Client ID",
                "Apple Ads → Account Settings → API → Client ID", FieldType.STRING, true),
            ConnectorConfigField.userInput("teamId", "Team ID",
                "Apple Ads → Account Settings → API → Team ID", FieldType.STRING, true),
            ConnectorConfigField.userInput("keyId", "Key ID",
                "Apple Ads → Account Settings → API → Key ID", FieldType.STRING, true),
            // Keyed "apiKey" so the framework stores it as the ENCRYPTED secret (ctx.accessToken()).
            ConnectorConfigField.userInput("apiKey", "Private Key (.p8)",
                "Contents of the .p8 private key from Apple Ads → Account Settings → API",
                FieldType.SECRET, true),
            ConnectorConfigField.userInput("orgId", "Org ID",
                "Your Apple Ads org ID (from GET /api/v5/acls)", FieldType.STRING, true),
            ConnectorConfigField.userInput("campaignId", "Campaign ID",
                "Campaign ID for keyword/search-term reports", FieldType.STRING, false)
        ));
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofHours(6); }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        AppleAdsTokenService.Credentials creds = credentials(ctx);
        String orgId = str(ctx.configValue("orgId"));
        if (!creds.complete() || isBlank(orgId)) {
            return ConnectorData.setupRequired("Apple Search Ads credentials not configured");
        }

        String token;
        try {
            token = tokenService.accessToken(ctx.connectionId(), creds);
        } catch (Exception e) {
            log.warn("Apple Search Ads token mint failed: {}", e.getMessage());
            return ConnectorData.degraded("Check Apple Search Ads credentials", Map.of());
        }

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(WINDOW_DAYS);
        HttpHeaders headers = headers(token, orgId);

        try {
            Map<String, Object> campaignReport = postReport(
                    API_BASE + "/reports/campaigns",
                    campaignReportBody(start, end), headers);

            Map<String, Object> series = aggregateCampaignSeries(campaignReport);

            // Keyword/search-term reports are optional and tolerated-failing; only attempted with a campaign.
            String campaignId = str(ctx.configValue("campaignId"));
            List<Map<String, Object>> topKeywords = List.of();
            List<Map<String, Object>> topSearchTerms = List.of();
            if (!isBlank(campaignId)) {
                topKeywords = safeTopKeywords(campaignId, start, end, headers);
                topSearchTerms = safeTopSearchTerms(campaignId, start, end, headers);
            }

            Map<String, Object> data = new HashMap<>(series);
            data.put("topKeywords", topKeywords);
            data.put("topSearchTerms", topSearchTerms);
            return ConnectorData.healthy(data);

        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                return ConnectorData.degraded("Check Apple Search Ads credentials", Map.of());
            }
            if (status == 429) {
                return ConnectorData.degraded("Rate limited — try again later", Map.of());
            }
            log.warn("Apple Search Ads API error: {}", e.getStatusCode());
            return ConnectorData.degraded("Apple Search Ads API error: " + e.getStatusCode(), Map.of());
        } catch (Exception e) {
            log.warn("Apple Search Ads fetch failed: {}", e.getMessage());
            return ConnectorData.degraded("Failed to fetch data: " + e.getMessage(), Map.of());
        }
    }

    @Override
    public IntegrationToolSpec getToolSpec() {
        return new IntegrationToolSpec(
            "Campaign installs, spend, and keyword performance from Apple Search Ads",
            List.of(
                new ToolOperation("campaign_report",
                    "Campaign-level installs, downloads, and spend time series (last 30 days)",
                    Map.of(),
                    "{installs:[{date,installs}], downloads:[{date,downloads}], spend:[{date,spend}]}",
                    List.of("installs", "downloads", "spend")),
                new ToolOperation("keyword_report",
                    "Top keywords by installs with spend, taps, and TTR (last 30 days; requires campaignId)",
                    Map.of("campaignId", "Campaign ID configured in connection settings"),
                    "{topKeywords:[{keyword,installs,taps,spend,ttr}]}",
                    List.of("topKeywords"))
            )
        );
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        AppleAdsTokenService.Credentials creds = credentials(ctx);
        String orgId = str(ctx.configValue("orgId"));
        if (!creds.complete() || isBlank(orgId)) {
            return ConnectorHealth.SETUP_REQUIRED;
        }
        try {
            String token = tokenService.accessToken(ctx.connectionId(), creds);
            restTemplate.exchange(API_BASE + "/acls", HttpMethod.GET,
                    new HttpEntity<>(headers(token, orgId)), Map.class);
            return ConnectorHealth.HEALTHY;
        } catch (Exception e) {
            return ConnectorHealth.DEGRADED;
        }
    }

    // ── Credential / header plumbing ────────────────────────────────────────

    /** The .p8 is the encrypted secret (ctx.accessToken()); the rest are plaintext config values. */
    private AppleAdsTokenService.Credentials credentials(ConnectionContext ctx) {
        return new AppleAdsTokenService.Credentials(
                str(ctx.configValue("clientId")),
                str(ctx.configValue("teamId")),
                str(ctx.configValue("keyId")),
                ctx.accessToken());
    }

    private HttpHeaders headers(String token, String orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-AP-Context", "orgId=" + orgId);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postReport(String url, Map<String, Object> body, HttpHeaders headers) {
        ResponseEntity<Map> resp = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        return resp.getBody() != null ? resp.getBody() : Map.of();
    }

    // ── Report request bodies ───────────────────────────────────────────────

    private Map<String, Object> campaignReportBody(LocalDate start, LocalDate end) {
        return Map.of(
            "startTime", start.format(DATE),
            "endTime", end.format(DATE),
            "granularity", "DAILY",
            "timeZone", "ORTZ",
            "selector", Map.of(
                "orderBy", List.of(Map.of("field", "impressions", "sortOrder", "DESCENDING")),
                "pagination", Map.of("offset", 0, "limit", 1000)
            ),
            "returnRowTotals", true,
            "returnGrandTotals", true,
            "returnRecordsWithNoMetrics", false
        );
    }

    private Map<String, Object> termReportBody(LocalDate start, LocalDate end, String orderByField) {
        return Map.of(
            "startTime", start.format(DATE),
            "endTime", end.format(DATE),
            "granularity", "DAILY",
            "timeZone", "ORTZ",
            "selector", Map.of(
                "orderBy", List.of(Map.of("field", orderByField, "sortOrder", "DESCENDING")),
                "pagination", Map.of("offset", 0, "limit", 50)
            ),
            "returnRowTotals", true,
            "returnGrandTotals", true,
            "returnRecordsWithNoMetrics", false
        );
    }

    // ── Campaign report → time series ───────────────────────────────────────

    /**
     * Aggregate the per-campaign daily granularity buckets into date-keyed time series, summing across
     * campaigns. Money fields arrive as {@code {amount, currency}} objects; we extract amount as a double.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> aggregateCampaignSeries(Map<String, Object> report) {
        // date -> accumulated metrics
        Map<String, double[]> byDate = new TreeMap<>();
        // indices: 0 newDownloads, 1 installs, 2 localSpend, 3 avgCPA, 4 avgCPT, 5 conversionRateSum, 6 rowCount

        for (Map<String, Object> row : rows(report)) {
            Object granObj = row.get("granularity");
            if (!(granObj instanceof List<?> buckets)) continue;
            for (Object b : buckets) {
                if (!(b instanceof Map<?, ?> bucketRaw)) continue;
                Map<String, Object> bucket = (Map<String, Object>) bucketRaw;
                String date = str(bucket.get("date"));
                if (isBlank(date)) continue;
                double[] acc = byDate.computeIfAbsent(date, k -> new double[7]);
                acc[0] += num(bucket.get("newDownloads"));
                acc[1] += num(bucket.get("installs"));
                acc[2] += money(bucket.get("localSpend"));
                acc[3] += money(bucket.get("avgCPA"));
                acc[4] += money(bucket.get("avgCPT"));
                acc[5] += num(bucket.get("conversionRate"));
                acc[6] += 1;
            }
        }

        List<Map<String, Object>> installsSeries = new ArrayList<>();
        List<Map<String, Object>> spendSeries = new ArrayList<>();
        List<Map<String, Object>> effSeries = new ArrayList<>();
        for (var e : byDate.entrySet()) {
            String date = e.getKey();
            double[] a = e.getValue();
            double campaigns = a[6] > 0 ? a[6] : 1;
            installsSeries.add(Map.of("date", date, "newDownloads", a[0], "installs", a[1]));
            spendSeries.add(Map.of("date", date, "localSpend", a[2]));
            // CPA/CPT/conversionRate are rate metrics — average across campaigns rather than summing.
            effSeries.add(Map.of(
                "date", date,
                "avgCPA", a[3] / campaigns,
                "avgCPT", a[4] / campaigns,
                "conversionRate", a[5] / campaigns
            ));
        }

        Map<String, Object> out = new HashMap<>();
        out.put("installsSeries", installsSeries);
        out.put("spendSeries", spendSeries);
        out.put("effSeries", effSeries);
        return out;
    }

    // ── Keyword / search-term reports (tolerated-failing) ───────────────────

    private List<Map<String, Object>> safeTopKeywords(String campaignId, LocalDate start, LocalDate end,
                                                      HttpHeaders headers) {
        try {
            Map<String, Object> report = postReport(
                    API_BASE + "/reports/campaigns/" + campaignId + "/keywords",
                    termReportBody(start, end, "newDownloads"), headers);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows(report)) {
                Map<String, Object> meta = metadata(row);
                Map<String, Object> m = totals(row);
                String keyword = firstNonBlank(str(meta.get("keyword")), str(meta.get("keywordText")));
                out.add(Map.of(
                    "keyword", keyword,
                    "newDownloads", num(m.get("newDownloads")),
                    "localSpend", money(m.get("localSpend")),
                    "avgCPA", money(m.get("avgCPA")),
                    "taps", num(m.get("taps"))
                ));
            }
            return out;
        } catch (Exception e) {
            log.warn("Apple Search Ads keyword report failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> safeTopSearchTerms(String campaignId, LocalDate start, LocalDate end,
                                                         HttpHeaders headers) {
        try {
            Map<String, Object> report = postReport(
                    API_BASE + "/reports/campaigns/" + campaignId + "/searchterms",
                    termReportBody(start, end, "newDownloads"), headers);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows(report)) {
                Map<String, Object> meta = metadata(row);
                Map<String, Object> m = totals(row);
                String term = firstNonBlank(str(meta.get("searchTermText")), str(meta.get("searchTerm")));
                out.add(Map.of(
                    "searchTerm", term,
                    "newDownloads", num(m.get("newDownloads")),
                    "taps", num(m.get("taps")),
                    "conversionRate", num(m.get("conversionRate"))
                ));
            }
            return out;
        } catch (Exception e) {
            log.warn("Apple Search Ads search-term report failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Response navigation helpers ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> report) {
        Object dataObj = report.get("data");
        if (!(dataObj instanceof Map<?, ?> data)) return List.of();
        Object respObj = ((Map<String, Object>) data).get("reportingDataResponse");
        if (!(respObj instanceof Map<?, ?> resp)) return List.of();
        Object rowObj = ((Map<String, Object>) resp).get("row");
        if (!(rowObj instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Map<String, Object> row) {
        Object meta = row.get("metadata");
        return meta instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Row-level totals for keyword/term rows (the single-bucket aggregate over the window). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> totals(Map<String, Object> row) {
        Object total = row.get("total");
        if (total instanceof Map<?, ?> m) return (Map<String, Object>) m;
        // Fall back to summing daily buckets if a row total isn't present.
        Object granObj = row.get("granularity");
        if (granObj instanceof List<?> buckets && !buckets.isEmpty()) {
            Object first = buckets.get(0);
            if (first instanceof Map<?, ?> fm) return (Map<String, Object>) fm;
        }
        return Map.of();
    }

    // ── Scalar coercion ─────────────────────────────────────────────────────

    /** Extract a money amount: Apple returns {@code {amount:"1.23", currency:"USD"}}. */
    private static double money(Object value) {
        if (value instanceof Map<?, ?> m) {
            return num(m.get("amount"));
        }
        return num(value);
    }

    private static double num(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        if (!isBlank(a)) return a;
        if (!isBlank(b)) return b;
        return "";
    }
}
