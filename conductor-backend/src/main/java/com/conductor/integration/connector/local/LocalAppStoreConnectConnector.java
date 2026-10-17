package com.conductor.integration.connector.local;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsSource;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FetchConnector;
import com.conductor.integration.FieldType;
import com.conductor.integration.MetricChannel;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Local-profile stub for App Store Connect: a deterministic 90-day series (the newest two days are left out, as
 * Apple publishes late) so the channel overview renders without credentials.
 */
@Component
@Profile("local")
@Primary
public class LocalAppStoreConnectConnector implements FetchConnector, ChannelMetricsSource {

    private static final String[] SOURCES = {"APP_STORE_SEARCH", "APP_STORE_BROWSE", "APP_REFERRER", "WEB_REFERRER"};
    private static final double[] SHARE = {0.45, 0.30, 0.15, 0.10};

    @Override
    public String getId() { return "app-store-connect"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("app-store-connect", "App Store Connect", ConnectorCategory.MARKETING,
                "App Store impressions, page views, downloads and ratings for an iOS app", "ASC");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(false, List.of(
            ConnectorConfigField.userInput("issuerId", "Issuer ID", "App Store Connect API issuer", FieldType.STRING, true),
            ConnectorConfigField.userInput("keyId", "Key ID", "Key ID of the API key", FieldType.STRING, true),
            ConnectorConfigField.userInput("appId", "App Apple ID", "Numeric Apple ID of the app", FieldType.STRING, true),
            ConnectorConfigField.userInput("ratingsCountry", "Ratings country", "Two-letter country (default us)",
                FieldType.STRING, false),
            ConnectorConfigField.userInput("apiKey", "Private Key (.p8)", "Contents of the .p8 file",
                FieldType.SECRET, true)
        ));
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofHours(6); }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        return ConnectorData.healthy(Map.of("appId", "0", "rating_count", 128.0, "rating_avg", 4.6,
                "listing", listing()));
    }

    /** A believable generic listing, in the shape of the real {@code get_app_store_listing} output. */
    private static Map<String, Object> listing() {
        Map<String, Object> live = Map.of("version", "1.4.0", "state", "READY_FOR_SALE", "locales", List.of(
                Map.of("locale", "en-US", "name", "Sample App", "subtitle", "Plan your week",
                        "keywords", "planner,habits,routine,tasks", "promotionalText", "Now with widgets",
                        "descriptionLength", 1840),
                Map.of("locale", "de-DE", "name", "Sample App", "subtitle", "Plane deine Woche",
                        "keywords", "planer,gewohnheiten,aufgaben", "descriptionLength", 1710)));
        Map<String, Object> draft = Map.of("version", "1.5.0", "state", "PREPARE_FOR_SUBMISSION", "locales", List.of(
                Map.of("locale", "en-US", "name", "Sample App", "subtitle", "Plan your week",
                        "keywords", "planner,habits,routine,tasks,focus", "promotionalText", "Focus mode is here",
                        "descriptionLength", 1900)));
        return Map.of("appId", "0", "live", live, "draft", draft,
                "customProductPages", List.of(Map.of("id", "cpp-1", "name", "Habits", "visible", true)),
                "experimentsAvailable", true,
                "experiments", List.of(Map.of("name", "Icon test", "state", "COMPLETED")),
                "localesTotal", 2, "localesIncluded", 2, "truncated", false);
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) { return ConnectorHealth.HEALTHY; }

    @Override
    public MetricChannel channel() { return MetricChannel.STORE; }

    @Override
    public int backfillDays() { return 90; }

    @Override
    public int trailingDays() { return 3; }

    @Override
    public int maxDaysPerPull() { return 14; }

    @Override
    public boolean snapshot(String metric) { return metric.equals("rating_count") || metric.equals("rating_avg"); }

    @Override
    public List<ChannelMetricPoint> pullDaily(ConnectionContext ctx, LocalDate from, LocalDate to) {
        List<ChannelMetricPoint> points = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate lastPublished = today.minusDays(2);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (day.isAfter(lastPublished)) continue;
            double impressions = LocalChannelSeries.value("asc-impr", day, 4000);
            double pageViews = Math.round(impressions * 0.12);
            double downloads = Math.round(pageViews * 0.30);
            double redownloads = Math.round(downloads * 0.18);
            double[] daily = {impressions, Math.round(impressions * 0.7), pageViews, Math.round(pageViews * 0.8),
                    downloads, redownloads};
            String[] names = {"impressions", "impressions_unique", "page_views", "page_views_unique", "downloads",
                    "redownloads"};
            for (int m = 0; m < names.length; m++) {
                double total = 0;
                for (int s = 0; s < SOURCES.length; s++) {
                    double v = Math.round(daily[m] * SHARE[s]);
                    total += v;
                    points.add(ChannelMetricPoint.of(names[m], "source:" + SOURCES[s], day, v));
                }
                points.add(ChannelMetricPoint.total(names[m], day, total));
            }
        }
        if (!to.isBefore(today)) {
            points.add(ChannelMetricPoint.total("rating_count", to, 128 + (to.toEpochDay() % 7)));
            points.add(ChannelMetricPoint.total("rating_avg", to, 4.6));
        }
        return points;
    }
}
