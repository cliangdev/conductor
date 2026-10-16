package com.conductor.marketing.overview;

import com.conductor.integration.MetricChannel;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What the marketing overview knows about each channel's daily metrics: label, unit, how a window rolls
 * up, which direction is good, and which dimension tables a channel offers. Code, not data: the
 * connectors write {@code channel_metric_daily} rows under these keys and this is the single place that
 * says how to read them back.
 *
 * <p>Order matters. The first {@link Channel#headline()} visible entries of a channel's metric list are
 * its headline metrics, the ones the overview page shows without drilling in.
 */
public final class MetricCatalog {

    public enum Unit { COUNT, CURRENCY, PERCENT, POSITION }

    /**
     * SUM adds the daily counts in a window. LEVEL is a stock (followers): the latest value at or before
     * the window's last day, summed across the series it spans (connections and dimensions). DERIVED is
     * numerator over denominator, each rolled up over the window first, so the result is weighted rather
     * than an average of daily ratios.
     */
    public enum Aggregation { SUM, LEVEL, DERIVED }

    /** Which way a move reads as good news; also the default direction of a target. */
    public enum Good { UP, DOWN }

    /**
     * @param hidden         internal building block (for example position times impressions), never output
     * @param optional       omitted from output until the channel has reported at least one row for it
     * @param fromDimensions the rows carry their series in {@code dimension_key} (a platform), so the metric
     *                       is the sum across all dimension keys instead of the '' total row
     */
    public record Metric(String key, String label, Unit unit, Aggregation agg, Good good, boolean hidden,
                         boolean optional, boolean fromDimensions, String numerator, String denominator) {}

    /**
     * One column of a dimension table. {@code source} is the stored metric the column reads (ratio columns
     * read none and divide two other columns instead).
     */
    public record Column(String key, String source, String label, Unit unit, Aggregation agg,
                         String numerator, String denominator) {}

    public record Dimension(String key, String label, List<Column> columns) {}

    public record Channel(MetricChannel channel, String label, List<String> connectorIds, List<Metric> metrics,
                          List<Dimension> dimensions, int headline) {

        public Optional<Metric> metric(String key) {
            return metrics.stream().filter(m -> m.key().equals(key)).findFirst();
        }

        public List<Metric> visibleMetrics() {
            return metrics.stream().filter(m -> !m.hidden()).toList();
        }

        public List<Metric> headlineMetrics() {
            return visibleMetrics().stream().limit(headline).toList();
        }

        public boolean hasLevelMetrics() {
            return metrics.stream().anyMatch(m -> m.agg() == Aggregation.LEVEL);
        }

        public boolean usesDimensionSeries() {
            return metrics.stream().anyMatch(Metric::fromDimensions);
        }
    }

    /** One funnel stage: a metric of a channel, in funnel order. */
    public record FunnelStage(String key, String label, MetricChannel channel, String metric,
                              String unavailableReason, String conversionFrom) {}

    /** The ingest id of the feeds that write the daily metrics. */
    public static final String FEED_INGEST_ID = "channel_metrics";

    private static final java.util.Map<String, String> CONNECTOR_NAMES = java.util.Map.of(
            "posthog", "PostHog",
            "gsc", "Search Console",
            "apple-search-ads", "Apple Search Ads",
            "meta", "Meta",
            "youtube", "YouTube");

    /** The display name of a connector that feeds a channel; falls back to its id. */
    public static String connectorName(String connectorId) {
        return CONNECTOR_NAMES.getOrDefault(connectorId, connectorId);
    }

    private static Metric sum(String key, String label, Unit unit, Good good) {
        return new Metric(key, label, unit, Aggregation.SUM, good, false, false, false, null, null);
    }

    private static Metric optionalSum(String key, String label, Unit unit, Good good, boolean fromDimensions) {
        return new Metric(key, label, unit, Aggregation.SUM, good, false, true, fromDimensions, null, null);
    }

    private static Metric hiddenSum(String key) {
        return new Metric(key, key, Unit.COUNT, Aggregation.SUM, Good.UP, true, false, false, null, null);
    }

    private static Metric level(String key, String label, boolean optional) {
        return new Metric(key, label, Unit.COUNT, Aggregation.LEVEL, Good.UP, false, optional, true, null, null);
    }

    private static Metric derived(String key, String label, Unit unit, Good good, String numerator, String denominator) {
        return new Metric(key, label, unit, Aggregation.DERIVED, good, false, false, false, numerator, denominator);
    }

    private static Column sumColumn(String key, String source, String label, Unit unit) {
        return new Column(key, source, label, unit, Aggregation.SUM, null, null);
    }

    private static Column levelColumn(String key, String label) {
        return new Column(key, key, label, Unit.COUNT, Aggregation.LEVEL, null, null);
    }

    private static Column ratioColumn(String key, String label, Unit unit, String numerator, String denominator) {
        return new Column(key, null, label, unit, Aggregation.DERIVED, numerator, denominator);
    }

    private static final List<Channel> CHANNELS = List.of(
            new Channel(MetricChannel.WEB, "Web", List.of("posthog"),
                    List.of(
                            sum("visitors", "Visitors", Unit.COUNT, Good.UP),
                            sum("sessions", "Sessions", Unit.COUNT, Good.UP),
                            sum("pageviews", "Page views", Unit.COUNT, Good.UP),
                            optionalSum("conversion_clicks", "Conversion clicks", Unit.COUNT, Good.UP, false)),
                    List.of(new Dimension("referrers", "Top referrers",
                            List.of(sumColumn("sessions", "sessions_by_referrer", "Sessions", Unit.COUNT)))),
                    4),
            new Channel(MetricChannel.SEARCH, "Search", List.of("gsc"),
                    List.of(
                            sum("clicks", "Clicks", Unit.COUNT, Good.UP),
                            sum("impressions", "Impressions", Unit.COUNT, Good.UP),
                            derived("ctr", "Click-through rate", Unit.PERCENT, Good.UP, "clicks", "impressions"),
                            derived("position", "Average position", Unit.POSITION, Good.DOWN,
                                    "position_x_impressions", "impressions"),
                            hiddenSum("position_x_impressions")),
                    List.of(new Dimension("queries", "Top queries", List.of(
                            sumColumn("clicks", "query_clicks", "Clicks", Unit.COUNT),
                            sumColumn("impressions", "query_impressions", "Impressions", Unit.COUNT),
                            ratioColumn("ctr", "Click-through rate", Unit.PERCENT, "clicks", "impressions")))),
                    4),
            // TikTok's follower and video counts need the user.info.stats scope, which Conductor does not
            // request, so TikTok feeds no account stats; its posts still count through post metrics.
            new Channel(MetricChannel.SOCIAL, "Social", List.of("meta", "youtube"),
                    List.of(
                            level("followers", "Followers", false),
                            level("media_count", "Posts", true),
                            level("channel_views", "Channel views", true),
                            optionalSum("website_clicks", "Website clicks", Unit.COUNT, Good.UP, true)),
                    List.of(new Dimension("platforms", "By platform", List.of(
                            levelColumn("followers", "Followers"),
                            levelColumn("media_count", "Posts"),
                            levelColumn("channel_views", "Channel views"),
                            sumColumn("website_clicks", "website_clicks", "Website clicks", Unit.COUNT)))),
                    4),
            new Channel(MetricChannel.STORE, "App Store", List.of(),
                    List.of(
                            sum("impressions", "Impressions", Unit.COUNT, Good.UP),
                            sum("page_views", "Page views", Unit.COUNT, Good.UP),
                            sum("downloads", "Downloads", Unit.COUNT, Good.UP)),
                    List.of(),
                    3),
            new Channel(MetricChannel.ADS, "Ads", List.of("apple-search-ads"),
                    List.of(
                            sum("spend", "Spend", Unit.CURRENCY, Good.DOWN),
                            sum("installs", "Installs", Unit.COUNT, Good.UP),
                            derived("cost_per_install", "Cost per install", Unit.CURRENCY, Good.DOWN,
                                    "spend", "installs"),
                            sum("taps", "Taps", Unit.COUNT, Good.UP),
                            sum("impressions", "Impressions", Unit.COUNT, Good.UP),
                            derived("tap_through_rate", "Tap-through rate", Unit.PERCENT, Good.UP,
                                    "taps", "impressions")),
                    List.of(new Dimension("campaigns", "Campaigns", List.of(
                            sumColumn("spend", "spend", "Spend", Unit.CURRENCY),
                            sumColumn("impressions", "impressions", "Impressions", Unit.COUNT),
                            sumColumn("taps", "taps", "Taps", Unit.COUNT),
                            sumColumn("installs", "installs", "Installs", Unit.COUNT),
                            ratioColumn("cost_per_install", "Cost per install", Unit.CURRENCY, "spend", "installs")))),
                    4));

    /** Funnel order. A stage with no data is reported unavailable with its reason, never dropped. */
    private static final List<FunnelStage> FUNNEL = List.of(
            new FunnelStage("web_visitors", "Web visitors", MetricChannel.WEB, "visitors",
                    "Connect web analytics", null),
            new FunnelStage("web_conversion_clicks", "Conversion clicks", MetricChannel.WEB, "conversion_clicks",
                    "Set a conversion event on the web analytics connection", "web_visitors"),
            new FunnelStage("store_impressions", "Store impressions", MetricChannel.STORE, "impressions",
                    "Connect App Store Connect (coming soon)", null),
            new FunnelStage("store_page_views", "Store page views", MetricChannel.STORE, "page_views",
                    "Connect App Store Connect (coming soon)", "store_impressions"),
            new FunnelStage("store_downloads", "Downloads", MetricChannel.STORE, "downloads",
                    "Connect App Store Connect (coming soon)", "store_page_views"),
            new FunnelStage("ads_taps", "Ad taps", MetricChannel.ADS, "taps", "Connect an ads account", null),
            new FunnelStage("ads_installs", "Ad installs", MetricChannel.ADS, "installs", "Connect an ads account",
                    "ads_taps"),
            new FunnelStage("product_stages", "In-app stages (first open → paid)", MetricChannel.PRODUCT, "stages",
                    "Connect product metrics (coming soon)", null));

    private MetricCatalog() {
    }

    public static List<Channel> channels() {
        return CHANNELS;
    }

    public static List<FunnelStage> funnel() {
        return FUNNEL;
    }

    /** The catalog channel for a caller-supplied name (case-insensitive), or empty for an unknown one. */
    public static Optional<Channel> channel(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return CHANNELS.stream().filter(c -> c.channel().name().equals(normalized)).findFirst();
    }

    public static Optional<Channel> channel(MetricChannel channel) {
        return CHANNELS.stream().filter(c -> c.channel() == channel).findFirst();
    }
}
