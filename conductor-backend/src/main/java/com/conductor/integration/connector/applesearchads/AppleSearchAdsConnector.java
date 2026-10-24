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
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
public class AppleSearchAdsConnector implements FetchConnector, ChannelMetricsSource {

    private static final Logger log = LoggerFactory.getLogger(AppleSearchAdsConnector.class);

    private static final String API_BASE = "https://api.searchads.apple.com/api/v5";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE; // yyyy-MM-dd
    private static final int WINDOW_DAYS = 30;

    private final RestTemplate restTemplate;
    private final AppleAdsTokenService tokenService;
    private final Clock clock;

    public AppleSearchAdsConnector() {
        this.restTemplate = ConnectorHttp.restTemplate();
        this.tokenService = new AppleAdsTokenService(this.restTemplate);
        this.clock = Clock.systemUTC();
    }

    /** Test seam: inject a mock RestTemplate and a stubbed token service. */
    AppleSearchAdsConnector(RestTemplate restTemplate, AppleAdsTokenService tokenService) {
        this(restTemplate, tokenService, Clock.systemUTC());
    }

    /** Test seam that also pins "today" (current-state reads only run on a pull that reaches today). */
    AppleSearchAdsConnector(RestTemplate restTemplate, AppleAdsTokenService tokenService, Clock clock) {
        this.restTemplate = restTemplate;
        this.tokenService = tokenService;
        this.clock = clock;
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
                FieldType.SECRET_TEXT, true),
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

    /**
     * UTC days, like every other channel and the product backend's feed: the overview compares ad spend by day.
     * (Only the search-term report is ORTZ-only.)
     */
    private Map<String, Object> campaignReportBody(LocalDate start, LocalDate end) {
        return Map.of(
            "startTime", start.format(DATE),
            "endTime", end.format(DATE),
            "granularity", "DAILY",
            "timeZone", "UTC",
            "selector", Map.of(
                "orderBy", List.of(Map.of("field", "impressions", "sortOrder", "DESCENDING")),
                "pagination", Map.of("offset", 0, "limit", 1000)
            ),
            "returnRowTotals", true,
            "returnGrandTotals", true,
            "returnRecordsWithNoMetrics", false
        );
    }

    /** Keyword and ad reports are DAILY in UTC like the campaign rows, paged by {@code offset}. */
    private Map<String, Object> breakdownReportBody(LocalDate start, LocalDate end, int offset) {
        return Map.of(
            "startTime", start.format(DATE),
            "endTime", end.format(DATE),
            "granularity", "DAILY",
            "timeZone", "UTC",
            "selector", Map.of(
                "orderBy", List.of(Map.of("field", "impressions", "sortOrder", "DESCENDING")),
                "pagination", Map.of("offset", offset, "limit", REPORT_PAGE)
            ),
            "returnRowTotals", false,
            "returnGrandTotals", false,
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
                acc[1] += installs(bucket);
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

    // ── ChannelMetricsSource ────────────────────────────────────────────────

    /** DAILY granularity is limited to a 90-day span per report request. */
    private static final int MAX_SPAN_DAYS = 90;
    private static final String[] DAILY_METRICS = {"impressions", "taps", "installs"};

    @Override
    public MetricChannel channel() { return MetricChannel.ADS; }

    @Override
    public int backfillDays() { return 90; }

    /** Keeps each backfill pull short; see {@link ChannelMetricsSource#maxDaysPerPull()}. */
    @Override
    public int maxDaysPerPull() { return 30; }

    /** Apple restates installs (attribution windows) for a few days. */
    @Override
    public int trailingDays() { return 3; }

    /**
     * Per-day spend, impressions, taps and installs: totals across campaigns (empty dimension key), per
     * campaign ({@code campaign:{id}:{name}}), per keyword ({@code keyword:{id}:{text}}) and per ad
     * ({@code ad:{id}:{name}}); the id never contains a colon, so split on the second one. Totals sum the
     * campaign rows only. A server error or auth failure on any report fails the whole pull. A 4xx on one
     * campaign's keyword/ad report skips just that campaign and lists it in {@link ChannelMetricsPull#skippedCampaignIds()},
     * so the store keeps that campaign's stored keywords and ads while still pruning the others.
     */
    @Override
    public List<ChannelMetricPoint> pullDaily(ConnectionContext ctx, LocalDate from, LocalDate to) {
        return pullAll(ctx, from, to, false).points();
    }

    /** As {@link #pullDaily}, plus the current bid/status of keywords and ads when the window reaches today. */
    @Override
    public ChannelMetricsPull pull(ConnectionContext ctx, LocalDate from, LocalDate to) {
        return pullAll(ctx, from, to, true);
    }

    private ChannelMetricsPull pullAll(ConnectionContext ctx, LocalDate from, LocalDate to, boolean withState) {
        AppleAdsTokenService.Credentials creds = credentials(ctx);
        String orgId = str(ctx.configValue("orgId"));
        if (!creds.complete() || isBlank(orgId)) {
            throw ChannelMetricsSourceException.setupRequired("Apple Search Ads credentials not configured");
        }
        String token;
        try {
            token = tokenService.accessToken(ctx.connectionId(), creds);
        } catch (Exception e) {
            throw ChannelMetricsSourceException.setupRequired("Check Apple Search Ads credentials");
        }
        HttpHeaders headers = headers(token, orgId);

        // date -> metric -> total
        Map<LocalDate, Map<String, Double>> totals = new TreeMap<>();
        List<ChannelMetricPoint> points = new ArrayList<>();
        List<DimensionState> states = new ArrayList<>();
        Set<String> skippedCampaigns = new java.util.LinkedHashSet<>();
        // what the keyword and ad report rows say about each member, used when the state read does not cover it
        Map<String, Map<String, Object>> reportStates = new LinkedHashMap<>();
        try {
            List<LocalDate[]> chunks = new ArrayList<>();
            LocalDate start = from;
            while (!start.isAfter(to)) {
                LocalDate end = start.plusDays(MAX_SPAN_DAYS - 1L);
                if (end.isAfter(to)) end = to;
                chunks.add(new LocalDate[]{start, end});
                start = end.plusDays(1);
            }
            for (LocalDate[] chunk : chunks) {
                Map<String, Object> report = postReport(API_BASE + "/reports/campaigns",
                        campaignReportBody(chunk[0], chunk[1]), headers);
                collect(report, totals, points);
            }

            List<Map<String, Object>> campaigns = new ArrayList<>();
            for (Map<String, Object> campaign : getPaged("/campaigns", headers)) {
                if (overlaps(campaign, from, to)) campaigns.add(campaign);
            }
            for (Map<String, Object> campaign : campaigns) {
                String campaignId = str(campaign.get("id"));
                if (isBlank(campaignId)) continue;
                if (!supportsKeywordReports(campaign)) continue;
                try {
                    List<ChannelMetricPoint> campaignPoints = new ArrayList<>();
                    Map<String, Map<String, Object>> campaignStates = new LinkedHashMap<>();
                    for (LocalDate[] chunk : chunks) {
                        for (Map<String, Object> row : pagedReportRows(
                                API_BASE + "/reports/campaigns/" + campaignId + "/keywords", chunk, headers)) {
                            collectKeywordRow(row, campaignPoints, campaignStates);
                        }
                        for (Map<String, Object> row : pagedReportRows(
                                API_BASE + "/reports/campaigns/" + campaignId + "/ads", chunk, headers)) {
                            collectAdRow(row, campaignPoints, campaignStates);
                        }
                    }
                    points.addAll(campaignPoints);
                    reportStates.putAll(campaignStates);
                } catch (HttpClientErrorException e) {
                    int status = e.getStatusCode().value();
                    if (status == 401 || status == 403 || status == 429) throw e;
                    log.warn("Apple Search Ads keyword/ad report skipped for campaign {}: {}", campaignId, status);
                    skippedCampaigns.add(campaignId);
                }
            }
            if (withState && !to.isBefore(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
                for (Map<String, Object> campaign : campaigns) {
                    collectState(campaign, headers, states);
                }
                java.util.Set<String> read = new java.util.HashSet<>();
                states.forEach(s -> read.add(s.dimensionKey()));
                reportStates.forEach((key, state) -> {
                    if (!read.contains(key)) states.add(new DimensionState(key, state));
                });
            }
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw ChannelMetricsSourceException.setupRequired("Apple Search Ads refused the credentials (" + status + ")");
            }
            if (status == 429) {
                throw ChannelMetricsSourceException.degraded("Apple Search Ads rate limited the request, retrying later");
            }
            throw ChannelMetricsSourceException.degraded("Apple Search Ads API error: " + status);
        } catch (org.springframework.web.client.RestClientException e) {
            throw ChannelMetricsSourceException.degraded("Apple Search Ads request failed: " + e.getMessage());
        }
        totals.forEach((day, byMetric) -> byMetric.forEach(
                (metric, value) -> points.add(ChannelMetricPoint.total(metric, day, value))));
        return new ChannelMetricsPull(points, null, null, null, Set.of(), states, null, skippedCampaigns);
    }

    /** Longest dimension key we store (the state table's key column). */
    private static final int MAX_KEY = 190;
    /** Rows per report page (Apple's maximum). */
    private static final int REPORT_PAGE = 1000;
    private static final int MAX_PAGES = 200;

    static String dimensionKey(String prefix, String id, String name) {
        String key = prefix + ":" + (id == null ? "" : id) + ":" + (name == null ? "" : name);
        return key.length() > MAX_KEY ? key.substring(0, MAX_KEY) : key;
    }

    /** All rows of a keyword or ad report for one chunk, following {@code pagination.totalResults}. */
    private List<Map<String, Object>> pagedReportRows(String url, LocalDate[] chunk, HttpHeaders headers) {
        List<Map<String, Object>> out = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> report = postReport(url, breakdownReportBody(chunk[0], chunk[1], offset), headers);
            List<Map<String, Object>> rows = rows(report);
            out.addAll(rows);
            offset += rows.size();
            long total = totalResults(report);
            if (rows.isEmpty() || (total >= 0 ? offset >= total : rows.size() < REPORT_PAGE)) break;
        }
        return out;
    }

    /** Apple's {@code pagination.totalResults} (top level of the response), or -1 when absent. */
    @SuppressWarnings("unchecked")
    private static long totalResults(Map<String, Object> response) {
        Object pagination = response.get("pagination");
        if (!(pagination instanceof Map<?, ?>) && response.get("data") instanceof Map<?, ?> data) {
            pagination = ((Map<String, Object>) data).get("pagination");
        }
        if (pagination instanceof Map<?, ?> p && ((Map<String, Object>) p).get("totalResults") instanceof Number n) {
            return n.longValue();
        }
        return -1;
    }

    /** A GET list endpoint ({@code data: [...]}, {@code pagination}), all pages. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getPaged(String path, HttpHeaders headers) {
        List<Map<String, Object>> out = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    API_BASE + path + "?limit=" + REPORT_PAGE + "&offset=" + offset,
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = resp.getBody() != null ? resp.getBody() : Map.of();
            int added = 0;
            if (body.get("data") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        out.add((Map<String, Object>) m);
                        added++;
                    }
                }
            }
            offset += added;
            long total = totalResults(body);
            if (added == 0 || (total >= 0 ? offset >= total : added < REPORT_PAGE)) break;
        }
        return out;
    }

    /** Only Search Results campaigns have keyword reports; a campaign that does not say what it is is still tried. */
    private static boolean supportsKeywordReports(Map<String, Object> campaign) {
        String type = str(campaign.get("adChannelType"));
        return isBlank(type) || "SEARCH".equalsIgnoreCase(type);
    }

    /** False only when the campaign's own start/end dates prove it cannot have data in the window. */
    private static boolean overlaps(Map<String, Object> campaign, LocalDate from, LocalDate to) {
        LocalDate start = date(campaign.get("startTime"));
        LocalDate end = date(campaign.get("endTime"));
        return !(start != null && start.isAfter(to)) && !(end != null && end.isBefore(from));
    }

    private static LocalDate date(Object value) {
        String s = str(value);
        if (isBlank(s) || s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One keyword or ad report row to daily points; first-time installs are {@code tapNewDownloads}. */
    @SuppressWarnings("unchecked")
    private void addBreakdownPoints(Map<String, Object> row, String dimension, List<ChannelMetricPoint> points) {
        if (!(row.get("granularity") instanceof List<?> buckets)) return;
        for (Object b : buckets) {
            if (!(b instanceof Map<?, ?> raw)) continue;
            Map<String, Object> bucket = (Map<String, Object>) raw;
            LocalDate day = date(bucket.get("date"));
            if (day == null) continue;
            points.add(ChannelMetricPoint.of("spend", dimension, day, money(bucket.get("localSpend"))));
            points.add(ChannelMetricPoint.of("impressions", dimension, day, num(bucket.get("impressions"))));
            points.add(ChannelMetricPoint.of("taps", dimension, day, num(bucket.get("taps"))));
            Object firstTime = bucket.get("tapNewDownloads");
            points.add(ChannelMetricPoint.of("installs", dimension, day,
                    firstTime != null ? num(firstTime) : installs(bucket)));
            if (bucket.get("tapRedownloads") != null) {
                points.add(ChannelMetricPoint.of("redownloads", dimension, day, num(bucket.get("tapRedownloads"))));
            }
        }
    }

    /** Apple reports the default-product-page pseudo-ad as id -1 and may give it no name. */
    private static String adName(String adId, String name) {
        return isBlank(name) && "-1".equals(adId) ? "Default product page" : name;
    }

    /** {@code ad:{adGroupId}:{adId}:{name}}; -1 (default page) repeats in every ad group, so the group is part of the key. */
    static String adKey(String adGroupId, String adId, String name) {
        return dimensionKey("ad", adGroupId + ":" + adId, adName(adId, name));
    }

    /** First non-blank string among {@code fields} of {@code meta}. */
    private static String first(Map<String, Object> meta, String... fields) {
        for (String f : fields) {
            String v = str(meta.get(f));
            if (!isBlank(v)) return v;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static void putIfPresent(Map<String, Object> to, String key, Object value) {
        if (value != null) to.put(key, value);
    }

    /** A keyword report row: id/text in either shape; its own matchType/bid/status serve as fallback state. */
    private void collectKeywordRow(Map<String, Object> row, List<ChannelMetricPoint> points,
                                   Map<String, Map<String, Object>> reportStates) {
        Map<String, Object> meta = metadata(row);
        String id = first(meta, "keywordId", "id");
        if (isBlank(id)) return;
        String key = dimensionKey("keyword", id, first(meta, "keyword", "text"));
        addBreakdownPoints(row, key, points);
        Map<String, Object> state = new LinkedHashMap<>();
        // Apple omits matchType on a Search Match row
        state.put("matchType", isBlank(first(meta, "matchType")) ? "SEARCH_MATCH" : first(meta, "matchType"));
        putIfPresent(state, "status", first(meta, "keywordStatus", "status"));
        Map<String, Object> bid = map(meta.get("bidAmount") != null ? meta.get("bidAmount") : meta.get("bid"));
        if (bid.get("amount") != null) {
            state.put("bidAmount", num(bid.get("amount")));
            putIfPresent(state, "bidCurrency", bid.get("currency"));
        }
        putIfPresent(state, "adGroupId", first(meta, "adGroupId"));
        putIfPresent(state, "adGroupName", first(meta, "adGroupName"));
        putIfPresent(state, "campaignId", first(meta, "campaignId"));
        putIfPresent(state, "campaignName", first(meta, "campaignName"));
        reportStates.put(key, state);
    }

    /** An ad report row. Rows without an adGroupId are skipped: the key cannot be made unique without it. */
    private void collectAdRow(Map<String, Object> row, List<ChannelMetricPoint> points,
                              Map<String, Map<String, Object>> reportStates) {
        Map<String, Object> meta = metadata(row);
        String id = first(meta, "adId", "id");
        String adGroupId = first(meta, "adGroupId");
        if (isBlank(id) || isBlank(adGroupId)) return;
        String key = adKey(adGroupId, id, first(meta, "adName", "name"));
        addBreakdownPoints(row, key, points);
        Map<String, Object> state = new LinkedHashMap<>();
        putIfPresent(state, "status", first(meta, "status"));
        putIfPresent(state, "servingStatus", first(meta, "servingStatus"));
        Map<String, Object> creative = map(meta.get("creative"));
        putIfPresent(state, "creativeType", firstNonNull(creative.get("creativeType"), meta.get("creativeType")));
        putIfPresent(state, "productPageId", firstNonNull(
                map(map(creative.get("destination")).get("parameters")).get("productPageId"), meta.get("productPageId")));
        state.put("adGroupId", adGroupId);
        putIfPresent(state, "adGroupName", first(meta, "adGroupName"));
        putIfPresent(state, "campaignId", first(meta, "campaignId"));
        putIfPresent(state, "campaignName", first(meta, "campaignName"));
        reportStates.put(key, state);
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    // ── Current state (bid, status, match type) ─────────────────────────────

    /**
     * The campaign's ad groups, and their targeting keywords and ads, as current-state rows. Best effort: a
     * failure here leaves the previous state in place and never fails the metrics pull.
     */
    private void collectState(Map<String, Object> campaign, HttpHeaders headers, List<DimensionState> out) {
        String campaignId = str(campaign.get("id"));
        if (isBlank(campaignId)) return;
        try {
            for (Map<String, Object> adGroup : getPaged("/campaigns/" + campaignId + "/adgroups", headers)) {
                String adGroupId = str(adGroup.get("id"));
                if (isBlank(adGroupId)) continue;
                Map<String, Object> common = new LinkedHashMap<>();
                common.put("adGroupId", adGroupId);
                common.put("adGroupName", adGroup.get("name"));
                common.put("adGroupStatus", adGroup.get("status"));
                common.put("campaignId", campaignId);
                common.put("campaignName", campaign.get("name"));
                common.put("campaignStatus", campaign.get("status"));
                common.put("campaignServingStatus", campaign.get("servingStatus"));
                String base = "/campaigns/" + campaignId + "/adgroups/" + adGroupId;
                for (Map<String, Object> kw : getPaged(base + "/targetingkeywords", headers)) {
                    String id = str(kw.get("id"));
                    if (isBlank(id)) continue;
                    Map<String, Object> state = new LinkedHashMap<>(common);
                    state.put("status", kw.get("status"));
                    state.put("matchType", kw.get("matchType"));
                    if (kw.get("bidAmount") instanceof Map<?, ?> bid) {
                        state.put("bidAmount", num(bid.get("amount")));
                        state.put("bidCurrency", bid.get("currency"));
                    }
                    out.add(new DimensionState(dimensionKey("keyword", id, str(kw.get("text"))), state));
                }
                for (Map<String, Object> ad : getPaged(base + "/ads", headers)) {
                    String id = str(ad.get("id"));
                    if (isBlank(id)) continue;
                    Map<String, Object> state = new LinkedHashMap<>(common);
                    state.put("status", ad.get("status"));
                    state.put("servingStatus", ad.get("servingStatus"));
                    Map<String, Object> creative = map(ad.get("creative"));
                    state.put("creativeType", firstNonNull(creative.get("creativeType"), ad.get("creativeType")));
                    state.put("productPageId", firstNonNull(
                            map(map(creative.get("destination")).get("parameters")).get("productPageId"),
                            ad.get("productPageId")));
                    out.add(new DimensionState(adKey(adGroupId, id, str(ad.get("name"))), state));
                }
            }
        } catch (org.springframework.web.client.RestClientException e) {
            log.warn("Apple Search Ads state read failed for campaign {}: {}", campaignId, e.getMessage());
        }
    }

    /**
     * Installs for one report bucket. Apple Ads API v5 replaced {@code installs} with {@code tapInstalls},
     * {@code viewInstalls} and {@code totalInstalls}; tap installs are the installs (as the product backend
     * counts them), so they win, and the legacy {@code installs} field is the fallback.
     */
    private static double installs(Map<String, Object> bucket) {
        Object tap = bucket.get("tapInstalls");
        return num(tap != null ? tap : bucket.get("installs"));
    }

    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> report, Map<LocalDate, Map<String, Double>> totals,
                         List<ChannelMetricPoint> points) {
        for (Map<String, Object> row : rows(report)) {
            Map<String, Object> meta = metadata(row);
            String campaignId = str(meta.get("campaignId"));
            String name = str(meta.get("campaignName"));
            String dimension = "campaign:" + (campaignId == null ? "" : campaignId) + ":" + (name == null ? "" : name);
            if (!(row.get("granularity") instanceof List<?> buckets)) continue;
            for (Object b : buckets) {
                if (!(b instanceof Map<?, ?> raw)) continue;
                Map<String, Object> bucket = (Map<String, Object>) raw;
                String date = str(bucket.get("date"));
                if (isBlank(date)) continue;
                LocalDate day;
                try {
                    day = LocalDate.parse(date.length() > 10 ? date.substring(0, 10) : date);
                } catch (RuntimeException e) {
                    continue;
                }
                Map<String, Double> dayTotals = totals.computeIfAbsent(day, d -> new LinkedHashMap<>());
                double spend = money(bucket.get("localSpend"));
                dayTotals.merge("spend", spend, Double::sum);
                points.add(ChannelMetricPoint.of("spend", dimension, day, spend));
                for (String metric : DAILY_METRICS) {
                    double v = metric.equals("installs") ? installs(bucket) : num(bucket.get(metric));
                    dayTotals.merge(metric, v, Double::sum);
                    points.add(ChannelMetricPoint.of(metric, dimension, day, v));
                }
            }
        }
    }
}
