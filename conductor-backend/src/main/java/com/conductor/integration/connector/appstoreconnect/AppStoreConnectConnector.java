package com.conductor.integration.connector.appstoreconnect;

import com.conductor.integration.ChannelMetricPoint;
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
import java.time.Duration;
import java.time.LocalDate;
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
 * Reports API (impressions, page views, first-time downloads and redownloads, in total and per source type),
 * plus the public rating count and average. One connection per app.
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
    private static final Pattern NUMERIC = Pattern.compile("\\d+");
    static final String APP_ID_MESSAGE = "The App Apple ID must be numbers only, e.g. 1234567890 "
            + "(App Store Connect → the app → App Information)";
    static final String KEY_UNUSABLE_MESSAGE =
            "App Store Connect could not use the private key — paste the whole .p8 file";
    private static final Set<String> SNAPSHOT_METRICS = Set.of(RATING_COUNT, RATING_AVG);
    private static final String[] REPORT_METRICS = {
            AppStoreAnalyticsClient.M_IMPRESSIONS, AppStoreAnalyticsClient.M_IMPRESSIONS_UNIQUE,
            AppStoreAnalyticsClient.M_PAGE_VIEWS, AppStoreAnalyticsClient.M_PAGE_VIEWS_UNIQUE,
            AppStoreAnalyticsClient.M_DOWNLOADS, AppStoreAnalyticsClient.M_REDOWNLOADS};

    private final RestTemplate restTemplate;
    private final AppStoreConnectTokenService tokenService;
    private final AppStoreAnalyticsClient client;
    private final AppStoreListingClient listingClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public AppStoreConnectConnector() {
        this(ConnectorHttp.restTemplate(Duration.ofSeconds(10), Duration.ofSeconds(60)),
                new AppStoreConnectTokenService());
    }

    /** Test seam. */
    AppStoreConnectConnector(RestTemplate restTemplate, AppStoreConnectTokenService tokenService) {
        this.restTemplate = restTemplate;
        this.tokenService = tokenService;
        this.client = new AppStoreAnalyticsClient(restTemplate);
        this.listingClient = new AppStoreListingClient(restTemplate);
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
            ConnectorConfigField.userInput("ratingsCountry", "Ratings country",
                "Two-letter App Store country the rating is read from (default us)", FieldType.STRING, false),
            // Keyed "apiKey" so the framework stores it as the ENCRYPTED secret (ctx.accessToken()).
            ConnectorConfigField.userInput("apiKey", "Private Key (.p8)",
                "Contents of the .p8 file downloaded when the API key was created", FieldType.SECRET, true)
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

    /**
     * Totals (empty dimension key) and per source type ({@code source:APP_STORE_SEARCH}, ...) for every report
     * day Apple has published within {@code from..to}; no row for a day it has not. When the window reaches
     * today the public rating count and average are added for today (best effort).
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

        Map<LocalDate, AppStoreAnalyticsClient.DayRows> days;
        try {
            days = client.fetchDays(token, appId, from, to);
        } catch (HttpClientErrorException e) {
            throw map(e, appId, false);
        } catch (AppStoreAnalyticsClient.ReportException e) {
            throw ChannelMetricsSourceException.degraded(e.getMessage());
        } catch (RestClientException e) {
            throw ChannelMetricsSourceException.degraded("App Store Connect request failed: " + e.getMessage());
        }

        List<ChannelMetricPoint> points = new ArrayList<>();
        days.forEach((day, rows) -> emit(day, rows, points));

        if (!to.isBefore(LocalDate.now(ZoneOffset.UTC))) {
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
        for (String metric : REPORT_METRICS) {
            double total = 0;
            boolean present = false;
            for (Map.Entry<String, Map<String, Double>> source : rows.bySource().entrySet()) {
                Double v = source.getValue().get(metric);
                if (v == null) continue;
                present = true;
                total += v;
                out.add(ChannelMetricPoint.of(metric, "source:" + source.getKey(), day, v));
            }
            if (present) {
                out.add(ChannelMetricPoint.total(metric, day, total));
            } else if (rows.coveredMetrics().contains(metric)) {
                // Apple's TSVs omit zero rows; a processed day with none for this metric really was zero.
                out.add(ChannelMetricPoint.total(metric, day, 0));
            }
        }
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
