package com.conductor.integration.connector.appstoreconnect;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.ChannelMetricsSource;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorHttp;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FetchConnector;
import com.conductor.integration.FieldType;
import com.conductor.integration.MetricChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * App Store Connect (FETCH connector): the daily App Store numbers of one iOS app, from Apple's Analytics
 * Reports API (impressions, page views, first-time downloads, redownloads and their sum {@code total_downloads},
 * in total and per source type), plus the public rating count and average. One connection per app.
 *
 * <p>With the optional {@code vendorNumber} it also reads Apple's Sales Reports, the number of record for
 * first-time downloads ({@code downloads_of_record}, total only) and which days that report was read
 * ({@code sales_report_days}). Sales never fail the Analytics part of a pull.
 *
 * <p>Auth is an ES256 JWT used directly as the bearer ({@link AppStoreConnectTokenService}); the {@code .p8}
 * is the encrypted secret ({@code ctx.accessToken()}), the identifiers are plaintext config.
 *
 * <p>Days follow Apple's own report dates. Apple publishes a day 1-2 days late, so the newest days simply
 * return no rows until it does; they are never stored as zero.
 */
@Component
@Profile("!local")
public class AppStoreConnectConnector implements FetchConnector, ChannelMetricsSource {

    private static final Logger log = LoggerFactory.getLogger(AppStoreConnectConnector.class);

    static final String ID = "app-store-connect";
    static final String LOOKUP_URL = "https://itunes.apple.com/lookup";
    static final String RATING_COUNT = "rating_count";
    static final String RATING_AVG = "rating_avg";
    static final String TOTAL_DOWNLOADS = "total_downloads";
    static final String DOWNLOADS_OF_RECORD = "downloads_of_record";
    static final String SALES_REPORT_DAYS = "sales_report_days";
    static final String CAMPAIGN_DOWNLOADS = "campaign_downloads";
    /**
     * Version 2: {@code total_downloads} exists. Version 3: {@code campaign_downloads} (per campaign token, from
     * the App Downloads Detailed report) exists. Version 4: a covered day stores 0 for each of
     * {@link #ZERO_FILLED_SOURCES} that has no row, as the day total already did. The version differing from the
     * one in the feed cursor makes the puller re-walk the backfill window once, so stored history gets it too.
     */
    static final int CONTRACT_VERSION = 4;
    /**
     * The sources the overview and the report read one by one (the per-source conversion rates, the organic search
     * split). Apple's TSVs omit zero rows, so on a day a report covers, one of these with no row for a metric really
     * had none: it is stored as 0, never left as a gap that would drop the day from a per-source sum or ratio.
     */
    static final List<String> ZERO_FILLED_SOURCES = List.of("APP_STORE_SEARCH", "APP_STORE_BROWSE", "APP_REFERRER",
            "WEB_REFERRER");
    /** Apple dates its Sales reports in Pacific time; a day is not published before that day has ended there. */
    private static final ZoneId SALES_REPORT_ZONE = ZoneId.of("America/Los_Angeles");
    /** A Sales report day this many days old (Pacific) is published if it ever will be: a 404 then means no sales. */
    static final int SALES_SETTLED_DAYS = 3;
    private static final Pattern NUMERIC = Pattern.compile("\\d+");
    static final String APP_ID_MESSAGE = "The App Apple ID must be numbers only, e.g. 1234567890 "
            + "(App Store Connect → the app → App Information)";
    static final String KEY_UNUSABLE_MESSAGE =
            "App Store Connect could not use the private key — paste the whole .p8 file";
    static final String VENDOR_NUMBER_MESSAGE = "The vendor number must be numbers only, e.g. 94514742 "
            + "(App Store Connect → Payments and Financial Reports), or left empty";
    private static final Set<String> SNAPSHOT_METRICS = Set.of(RATING_COUNT, RATING_AVG);
    private static final String[] REPORT_METRICS = {
            AppStoreAnalyticsClient.M_IMPRESSIONS, AppStoreAnalyticsClient.M_IMPRESSIONS_UNIQUE,
            AppStoreAnalyticsClient.M_PAGE_VIEWS, AppStoreAnalyticsClient.M_PAGE_VIEWS_UNIQUE,
            AppStoreAnalyticsClient.M_DOWNLOADS, AppStoreAnalyticsClient.M_REDOWNLOADS};

    private final RestTemplate restTemplate;
    private final AppStoreConnectTokenService tokenService;
    private final AppStoreAnalyticsClient client;
    private final AppStoreListingClient listingClient;
    private final AppStoreSalesClient salesClient;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public AppStoreConnectConnector() {
        this(ConnectorHttp.restTemplate(Duration.ofSeconds(10), Duration.ofSeconds(60)),
                new AppStoreConnectTokenService());
    }

    /** Test seam. */
    AppStoreConnectConnector(RestTemplate restTemplate, AppStoreConnectTokenService tokenService) {
        this(restTemplate, tokenService, Clock.systemUTC());
    }

    /** Test seam: the clock decides which report days Apple can have published. */
    AppStoreConnectConnector(RestTemplate restTemplate, AppStoreConnectTokenService tokenService, Clock clock) {
        this.restTemplate = restTemplate;
        this.tokenService = tokenService;
        this.client = new AppStoreAnalyticsClient(restTemplate);
        this.listingClient = new AppStoreListingClient(restTemplate);
        this.salesClient = new AppStoreSalesClient(restTemplate);
        this.clock = clock;
    }

    @Override
    public String getId() { return ID; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata(ID, "App Store Connect", ConnectorCategory.MARKETING,
                "App Store impressions, page views, downloads and ratings for an iOS app", "ASC");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(false, List.of(
            ConnectorConfigField.userInput("issuerId", "Issuer ID",
                "App Store Connect → Users and Access → Integrations → App Store Connect API (top of the page)",
                FieldType.STRING, true),
            ConnectorConfigField.userInput("keyId", "Key ID",
                "The Key ID of the API key you created (same page)", FieldType.STRING, true),
            ConnectorConfigField.userInput("appId", "App Apple ID",
                "The numeric Apple ID of the app, digits only (App Store Connect → the app → App Information)",
                FieldType.STRING, true),
            ConnectorConfigField.userInput("vendorNumber", "Vendor number",
                "Sales and Trends vendor number, from App Store Connect → Payments and Financial Reports. Optional: "
                    + "with it, downloads of record are read from Apple's Sales Reports", FieldType.STRING, false),
            ConnectorConfigField.userInput("providerToken", "App Store Connect provider token (pt)",
                "App Store Connect → App Analytics → Campaigns → Generate a Link (the number after pt=). Optional: "
                    + "with it, every new Post gets its own campaign link", FieldType.STRING, false),
            ConnectorConfigField.userInput("ratingsCountry", "Ratings country",
                "Two-letter App Store country the rating is read from (default us)", FieldType.STRING, false),
            // Keyed "apiKey" so the framework stores it as the ENCRYPTED secret (ctx.accessToken()).
            ConnectorConfigField.userInput("apiKey", "Private Key (.p8)",
                "Contents of the .p8 file downloaded when the API key was created", FieldType.SECRET_TEXT, true)
        ));
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofHours(6); }

    // -- FetchConnector -------------------------------------------------------------------------------

    /** A small status snapshot (the numbers themselves live in the channel metrics feed). */
    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        String appId = str(ctx.configValue("appId"));
        if (!credentials(ctx).complete() || isBlank(appId)) {
            return ConnectorData.setupRequired("App Store Connect credentials not configured");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("appId", appId);
        try {
            Map<String, Double> ratings = lookupRatings(appId, country(ctx));
            ratings.forEach(data::put);
        } catch (RuntimeException e) {
            log.warn("App Store rating lookup failed for app {}: {}", appId, e.getMessage());
        }
        readListing(ctx, appId, data);
        return ConnectorData.healthy(data);
    }

    /**
     * The store listing for the {@code get_app_store_listing} agent tool. Agent tool calls reach the connector with
     * no arguments, so this is always the whole (size-budgeted) listing. A failure is reported as
     * {@code listingError}; it never fails the status snapshot.
     */
    private void readListing(ConnectionContext ctx, String appId, Map<String, Object> data) {
        try {
            String token = tokenService.token(ctx.connectionId(), credentials(ctx));
            data.put("listing", listingClient.read(token, appId));
        } catch (HttpClientErrorException e) {
            data.put("listingError", httpMessage(e, appId, true));
        } catch (RuntimeException e) {
            log.warn("App Store listing read failed for app {}: {}", appId, e.getMessage());
            data.put("listingError", "App Store listing could not be read: " + e.getMessage());
        }
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        AppStoreConnectTokenService.Credentials creds = credentials(ctx);
        String appId = str(ctx.configValue("appId"));
        if (!creds.complete() || isBlank(appId) || !isNumeric(appId)) {
            return ConnectorHealth.SETUP_REQUIRED;
        }
        appId = appId.trim();
        boolean appStep = true;
        try {
            String token = tokenService.token(ctx.connectionId(), creds);
            client.getApp(token, appId);
            appStep = false;
            client.findOrCreateOngoingRequest(token, appId);
            return ConnectorHealth.HEALTHY;
        } catch (HttpClientErrorException e) {
            log.warn("App Store Connect health check for app {}: {}", appId, httpMessage(e, appId, appStep));
            int status = e.getStatusCode().value();
            return status == 401 || status == 403 || status == 404 ? ConnectorHealth.SETUP_REQUIRED
                    : ConnectorHealth.DEGRADED;
        } catch (IllegalStateException e) {
            log.warn("App Store Connect health check: {}", e.getMessage());
            return ConnectorHealth.SETUP_REQUIRED;
        } catch (RuntimeException e) {
            log.warn("App Store Connect health check failed: {}", e.getMessage());
            return ConnectorHealth.DEGRADED;
        }
    }

    /** One connection per app: the numeric App Apple ID. */
    @Override
    public String identityNoun() { return "app"; }

    @Override
    public Optional<String> identityKey(ConnectionContext ctx) {
        String appId = str(ctx.configValue("appId"));
        return isBlank(appId) ? Optional.empty() : Optional.of(appId.trim());
    }

    /**
     * Runs the same calls a pull makes (the app, then the ONGOING analytics request, creating it when missing) so a
     * bad Issuer ID, Key ID, .p8, role or app id is refused at connect time with the plain message a feed pull
     * would later report.
     */
    @Override
    public Optional<String> verifyCredentials(ConnectionContext ctx) {
        AppStoreConnectTokenService.Credentials creds = credentials(ctx);
        String appId = str(ctx.configValue("appId"));
        if (!creds.complete() || isBlank(appId)) {
            return Optional.of("Fill in the Issuer ID, Key ID, App Apple ID and the private key (.p8)");
        }
        if (!isNumeric(appId)) {
            return Optional.of(APP_ID_MESSAGE);
        }
        String vendorNumber = vendorNumber(ctx);
        if (vendorNumber != null && !isNumeric(vendorNumber)) {
            return Optional.of(VENDOR_NUMBER_MESSAGE);
        }
        appId = appId.trim();
        String token;
        try {
            token = tokenService.token(ctx.connectionId(), creds);
        } catch (RuntimeException e) {
            return Optional.of(KEY_UNUSABLE_MESSAGE);
        } finally {
            tokenService.evict(ctx.connectionId());
        }
        boolean appStep = true;
        try {
            client.getApp(token, appId);
            appStep = false;
            client.findOrCreateOngoingRequest(token, appId);
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            return Optional.of(httpMessage(e, appId, appStep));
        } catch (AppStoreAnalyticsClient.ReportException e) {
            return Optional.of(e.getMessage());
        } catch (RestClientException e) {
            return Optional.of("Could not reach App Store Connect to check the credentials: " + e.getMessage());
        }
    }

    // -- ChannelMetricsSource -------------------------------------------------------------------------

    @Override
    public MetricChannel channel() { return MetricChannel.STORE; }

    /** Apple only has data from when the report request was created; the puller backfills what exists. */
    @Override
    public int backfillDays() { return 90; }

    /** Apple restates the last couple of days. */
    @Override
    public int trailingDays() { return 3; }

    @Override
    public int maxDaysPerPull() { return 14; }

    @Override
    public boolean snapshot(String metric) { return SNAPSHOT_METRICS.contains(metric); }

    /** The same points as {@link #pullDaily}, tagged with {@link #CONTRACT_VERSION}. */
    @Override
    public ChannelMetricsPull pull(ConnectionContext ctx, LocalDate from, LocalDate to) {
        return new ChannelMetricsPull(pullDaily(ctx, from, to), null, null, null, Set.of(), List.of(),
                CONTRACT_VERSION);
    }

    /**
     * Totals (empty dimension key) and per source type ({@code source:APP_STORE_SEARCH}, ...) for every report
     * day Apple has published within {@code from..to}; no row for a day it has not. With a vendor number, the
     * Sales Reports days Apple has published are added (best effort, never failing the pull). When the window
     * reaches today the public rating count and average are added for today (best effort).
     */
    @Override
    public List<ChannelMetricPoint> pullDaily(ConnectionContext ctx, LocalDate from, LocalDate to) {
        AppStoreConnectTokenService.Credentials creds = credentials(ctx);
        String appId = str(ctx.configValue("appId"));
        if (!creds.complete() || isBlank(appId)) {
            throw ChannelMetricsSourceException.setupRequired("App Store Connect credentials not configured");
        }
        if (!isNumeric(appId)) {
            throw ChannelMetricsSourceException.setupRequired(APP_ID_MESSAGE);
        }
        appId = appId.trim();
        String token;
        try {
            token = tokenService.token(ctx.connectionId(), creds);
        } catch (RuntimeException e) {
            throw ChannelMetricsSourceException.setupRequired(KEY_UNUSABLE_MESSAGE);
        }

        // Check the app first: Apple answers 404 for an app the key cannot see, but 403 ("role") for its analytics.
        try {
            client.getApp(token, appId);
        } catch (HttpClientErrorException e) {
            throw map(e, appId, true);
        } catch (RestClientException e) {
            throw ChannelMetricsSourceException.degraded("App Store Connect request failed: " + e.getMessage());
        }

        AppStoreAnalyticsClient.Fetched fetched;
        try {
            fetched = client.fetch(token, appId, from, to);
        } catch (HttpClientErrorException e) {
            throw map(e, appId, false);
        } catch (AppStoreAnalyticsClient.ReportException e) {
            throw ChannelMetricsSourceException.degraded(e.getMessage());
        } catch (RestClientException e) {
            throw ChannelMetricsSourceException.degraded("App Store Connect request failed: " + e.getMessage());
        }

        List<ChannelMetricPoint> points = new ArrayList<>();
        fetched.days().forEach((day, rows) -> emit(day, rows, points));
        fetched.campaignDownloads().forEach((day, tokens) ->
                tokens.forEach((key, downloads) -> points.add(ChannelMetricPoint.of(CAMPAIGN_DOWNLOADS, key, day, downloads))));

        String vendorNumber = vendorNumber(ctx);
        if (vendorNumber != null) {
            points.addAll(pullSales(token, appId, vendorNumber, from, to));
        }

        if (!to.isBefore(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
            try {
                lookupRatings(appId, country(ctx)).forEach((metric, value) ->
                        points.add(ChannelMetricPoint.total(metric, to, value)));
            } catch (RuntimeException e) {
                log.warn("App Store rating lookup failed for app {} (skipped): {}", appId, e.getMessage());
            }
        }
        return points;
    }

    private static void emit(LocalDate day, AppStoreAnalyticsClient.DayRows rows, List<ChannelMetricPoint> out) {
        Map<String, Map<String, Double>> bySource = withZeroFilledSources(rows);
        Map<String, Double> dayTotals = new HashMap<>();
        for (String metric : REPORT_METRICS) {
            double total = 0;
            boolean present = false;
            for (Map.Entry<String, Map<String, Double>> source : bySource.entrySet()) {
                Double v = source.getValue().get(metric);
                if (v == null) continue;
                present = true;
                total += v;
                out.add(ChannelMetricPoint.of(metric, "source:" + source.getKey(), day, v));
            }
            if (present) {
                dayTotals.put(metric, total);
                out.add(ChannelMetricPoint.total(metric, day, total));
            } else if (rows.coveredMetrics().contains(metric)) {
                // Apple's TSVs omit zero rows; a processed day with none for this metric really was zero.
                dayTotals.put(metric, 0.0);
                out.add(ChannelMetricPoint.total(metric, day, 0));
            }
        }
        emitTotalDownloads(day, bySource, dayTotals, out);
    }

    /**
     * The day's rows by source, plus a 0 for each covered metric of each {@link #ZERO_FILLED_SOURCES} source with no
     * row for it. Other sources are left as Apple sent them, and a metric no report covers that day stays absent.
     */
    private static Map<String, Map<String, Double>> withZeroFilledSources(AppStoreAnalyticsClient.DayRows rows) {
        Map<String, Map<String, Double>> out = new java.util.LinkedHashMap<>();
        rows.bySource().forEach((source, metrics) -> out.put(source, new HashMap<>(metrics)));
        for (String source : ZERO_FILLED_SOURCES) {
            Map<String, Double> metrics = out.computeIfAbsent(source, s -> new HashMap<>());
            for (String metric : rows.coveredMetrics()) {
                metrics.putIfAbsent(metric, 0.0);
            }
        }
        out.values().removeIf(Map::isEmpty);
        return out;
    }

    /** First-time + redownloads, for the day and per source, wherever both inputs exist (never guessed as 0). */
    private static void emitTotalDownloads(LocalDate day, Map<String, Map<String, Double>> bySource,
                                           Map<String, Double> dayTotals, List<ChannelMetricPoint> out) {
        Double downloads = dayTotals.get(AppStoreAnalyticsClient.M_DOWNLOADS);
        Double redownloads = dayTotals.get(AppStoreAnalyticsClient.M_REDOWNLOADS);
        if (downloads != null && redownloads != null) {
            out.add(ChannelMetricPoint.total(TOTAL_DOWNLOADS, day, downloads + redownloads));
        }
        bySource.forEach((source, metrics) -> {
            Double d = metrics.get(AppStoreAnalyticsClient.M_DOWNLOADS);
            Double r = metrics.get(AppStoreAnalyticsClient.M_REDOWNLOADS);
            if (d != null && r != null) {
                out.add(ChannelMetricPoint.of(TOTAL_DOWNLOADS, "source:" + source, day, d + r));
            }
        });
    }

    // -- sales reports (downloads of record) -----------------------------------------------------------

    /**
     * One Sales report per day in {@code from..to} that Apple can have published. A day without a report writes
     * nothing. The first failure (a refused role, a rate limit, an unreadable file) ends the sales part of this
     * pull: the days already read are kept, the rest are tried again on the next pull, and Analytics is untouched.
     */
    private List<ChannelMetricPoint> pullSales(String token, String appId, String vendorNumber,
                                               LocalDate from, LocalDate to) {
        if (!isNumeric(vendorNumber)) {
            log.warn("App Store Connect vendor number for app {} is not numbers only; Sales Reports skipped", appId);
            return List.of();
        }
        LocalDate today = LocalDate.now(clock.withZone(SALES_REPORT_ZONE));
        LocalDate newest = today.minusDays(1);
        LocalDate settledThrough = today.minusDays(SALES_SETTLED_DAYS);
        List<ChannelMetricPoint> points = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to) && !day.isAfter(newest); day = day.plusDays(1)) {
            try {
                Optional<AppStoreSalesClient.SalesDay> report = salesClient.fetchDay(token, vendorNumber, appId, day,
                        !day.isAfter(settledThrough));
                if (report.isPresent()) {
                    points.add(ChannelMetricPoint.total(DOWNLOADS_OF_RECORD, day, report.get().firstTimeDownloads()));
                    points.add(ChannelMetricPoint.total(SALES_REPORT_DAYS, day, 1));
                }
            } catch (RuntimeException e) {
                log.warn("App Store Connect Sales Reports for app {} failed on {} (sales skipped for this pull): {}",
                        appId, day, e.getMessage());
                break;
            }
        }
        return points;
    }

    // -- ratings (public iTunes lookup) ----------------------------------------------------------------

    private Map<String, Double> lookupRatings(String appId, String country) {
        URI uri = URI.create(LOOKUP_URL + "?id=" + URLEncoder.encode(appId, StandardCharsets.UTF_8)
                + "&country=" + URLEncoder.encode(country, StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.ALL));
        String body = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        try {
            JsonNode first = mapper.readTree(body == null ? "{}" : body).path("results").path(0);
            Map<String, Double> out = new HashMap<>();
            if (first.hasNonNull("userRatingCount")) out.put(RATING_COUNT, first.get("userRatingCount").asDouble());
            if (first.hasNonNull("averageUserRating")) out.put(RATING_AVG, first.get("averageUserRating").asDouble());
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("rating lookup returned a response that is not JSON");
        }
    }

    /** The configured vendor number, trimmed; null when none is set (then Sales Reports are not read at all). */
    private static String vendorNumber(ConnectionContext ctx) {
        String raw = str(ctx.configValue("vendorNumber"));
        return isBlank(raw) ? null : raw.trim();
    }

    private static String country(ConnectionContext ctx) {
        String c = str(ctx.configValue("ratingsCountry"));
        return isBlank(c) ? "us" : c.trim().toLowerCase(java.util.Locale.ROOT);
    }

    // -- errors / plumbing -----------------------------------------------------------------------------

    private static ChannelMetricsSourceException map(HttpClientErrorException e, String appId, boolean appStep) {
        int status = e.getStatusCode().value();
        String message = httpMessage(e, appId, appStep);
        if (status == 401 || status == 403 || status == 404) {
            return ChannelMetricsSourceException.setupRequired(message);
        }
        if (status == 429) {
            return ChannelMetricsSourceException.degraded("App Store Connect rate limited the request, retrying later");
        }
        return ChannelMetricsSourceException.degraded("App Store Connect API error: " + status);
    }

    private static String httpMessage(HttpClientErrorException e, String appId, boolean appStep) {
        return switch (e.getStatusCode().value()) {
            case 401 -> "App Store Connect rejected the API key — check the Issuer ID, Key ID and .p8 file";
            case 403 -> "The API key's role can't read analytics reports — use a key with the Admin role, or a role "
                    + "that can read Analytics Reports";
            case 404 -> appStep
                    ? "No app with Apple ID " + appId + " is visible to this API key — check the App Apple ID"
                    : "App Store Connect cannot find the app's analytics — check the App Apple ID and that the key can see it";
            default -> "App Store Connect API error " + e.getStatusCode().value();
        };
    }

    private static AppStoreConnectTokenService.Credentials credentials(ConnectionContext ctx) {
        return new AppStoreConnectTokenService.Credentials(
                str(ctx.configValue("issuerId")), str(ctx.configValue("keyId")), ctx.accessToken());
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isNumeric(String s) {
        return s != null && NUMERIC.matcher(s.trim()).matches();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
