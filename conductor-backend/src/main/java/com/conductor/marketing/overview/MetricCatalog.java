package com.conductor.marketing.overview;

import com.conductor.entity.ChannelMetricDefinitionRow;
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

    public enum Unit { COUNT, CURRENCY, PERCENT, POSITION, RATING }

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
     * @param funnelOrder    position as a stage of the in-app funnel (a PRODUCT metric a feed declared), else null
     */
    public record Metric(String key, String label, Unit unit, Aggregation agg, Good good, boolean hidden,
                         boolean optional, boolean fromDimensions, String numerator, String denominator,
                         Integer funnelOrder) {}

    /**
     * One column of a dimension table. {@code source} is the stored metric the column reads (ratio columns
     * read none and divide two other columns instead).
     */
    public record Column(String key, String source, String label, Unit unit, Aggregation agg,
                         String numerator, String denominator) {}

    /** @param keyPrefix when set, only rows whose dimension key starts with it belong to the table (and it is stripped from labels) */
    public record Dimension(String key, String label, List<Column> columns, String keyPrefix) {
        public Dimension(String key, String label, List<Column> columns) {
            this(key, label, columns, null);
        }
    }

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

    /** The connector whose feeds fill the PRODUCT (in-app) channel; its metrics come from stored definitions. */
    public static final String PRODUCT_CONNECTOR_ID = "product-metrics";

    /** The ingest id of the feeds that write the daily metrics. */
    public static final String FEED_INGEST_ID = "channel_metrics";

    private static final java.util.Map<String, String> CONNECTOR_NAMES = java.util.Map.of(
            "posthog", "PostHog",
            "gsc", "Search Console",
            "apple-search-ads", "Apple Search Ads",
            "meta", "Meta",
            "youtube", "YouTube",
            "app-store-connect", "App Store Connect",
            PRODUCT_CONNECTOR_ID, "Product metrics");

    private static final java.util.Map<String, String> STORE_SOURCE_LABELS = java.util.Map.of(
            "APP_STORE_SEARCH", "App Store search",
            "APP_STORE_BROWSE", "App Store browse",
            "APP_REFERRER", "App referrer",
            "WEB_REFERRER", "Web referrer",
            "APP_CLIP", "App Clip",
            "INSTITUTIONAL_PURCHASE", "Institutional purchase",
            "UNAVAILABLE", "Unavailable",
            "OTHER", "Other");

    /** The dimension-key prefix of the App Store traffic-source rows ({@code source:APP_STORE_SEARCH}). */
    public static final String STORE_SOURCE_PREFIX = "source:";

    /** Human label for an App Store source type; an unknown type is shown as written. */
    public static String storeSourceLabel(String type) {
        return STORE_SOURCE_LABELS.getOrDefault(type, type);
    }

    /** The display name of a connector that feeds a channel; falls back to its id. */
    public static String connectorName(String connectorId) {
        return CONNECTOR_NAMES.getOrDefault(connectorId, connectorId);
    }

    private static Metric sum(String key, String label, Unit unit, Good good) {
        return new Metric(key, label, unit, Aggregation.SUM, good, false, false, false, null, null, null);
    }

    private static Metric optionalSum(String key, String label, Unit unit, Good good, boolean fromDimensions) {
        return new Metric(key, label, unit, Aggregation.SUM, good, false, true, fromDimensions, null, null, null);
    }

    private static Metric hiddenSum(String key) {
        return new Metric(key, key, Unit.COUNT, Aggregation.SUM, Good.UP, true, false, false, null, null, null);
    }

    private static Metric ratingLevel(String key, String label, Unit unit) {
        return new Metric(key, label, unit, Aggregation.LEVEL, Good.UP, false, true, true, null, null, null);
    }

    /**
     * A snapshot that is an average over another snapshot's population (the average rating of the ratings counted
     * by {@code weightKey}). Across connections it is the weighted mean, never the sum. The weight key rides in the
     * metric's {@code denominator}, which a LEVEL otherwise never uses.
     */
    private static Metric weightedRatingLevel(String key, String label, Unit unit, String weightKey) {
        return new Metric(key, label, unit, Aggregation.LEVEL, Good.UP, false, true, true, null, weightKey);
    }

    private static Metric level(String key, String label, boolean optional) {
        return new Metric(key, label, Unit.COUNT, Aggregation.LEVEL, Good.UP, false, optional, true, null, null, null);
    }

    private static Metric derived(String key, String label, Unit unit, Good good, String numerator, String denominator) {
        return new Metric(key, label, unit, Aggregation.DERIVED, good, false, false, false, numerator, denominator, null);
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
            new Channel(MetricChannel.STORE, "App Store", List.of("app-store-connect"),
                    List.of(
                            sum("impressions", "Impressions", Unit.COUNT, Good.UP),
                            sum("page_views", "Page views", Unit.COUNT, Good.UP),
                            sum("downloads", "Downloads", Unit.COUNT, Good.UP),
                            // Apple's definition: first-time downloads over unique impressions
                            derived("conversion_rate", "Conversion rate", Unit.PERCENT, Good.UP,
                                    "downloads", "impressions_unique"),
                            optionalSum("redownloads", "Redownloads", Unit.COUNT, Good.UP, false),
                            ratingLevel("rating_count", "Ratings", Unit.COUNT),
                            weightedRatingLevel("rating_avg", "Average rating", Unit.RATING, "rating_count"),
                            hiddenSum("impressions_unique")),
                    List.of(new Dimension("sources", "By traffic source", List.of(
                            sumColumn("impressions", "impressions", "Impressions", Unit.COUNT),
                            sumColumn("page_views", "page_views", "Page views", Unit.COUNT),
                            sumColumn("downloads", "downloads", "Downloads", Unit.COUNT),
                            ratioColumn("download_rate", "Downloads per page view", Unit.PERCENT,
                                    "downloads", "page_views")))),
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
                    4),
            // The in-app channel has no fixed metrics: each connected backend declares its own (see productChannel).
            new Channel(MetricChannel.PRODUCT, "In-app", List.of(PRODUCT_CONNECTOR_ID), List.of(), List.of(), 4));

    /** Funnel order. A stage with no data is reported unavailable with its reason, never dropped. */
    private static final List<FunnelStage> FUNNEL = List.of(
            new FunnelStage("web_visitors", "Web visitors", MetricChannel.WEB, "visitors",
                    "Connect web analytics", null),
            new FunnelStage("web_conversion_clicks", "Conversion clicks", MetricChannel.WEB, "conversion_clicks",
                    "Set a conversion event on the web analytics connection", "web_visitors"),
            new FunnelStage("store_impressions", "Store impressions", MetricChannel.STORE, "impressions",
                    "Connect App Store Connect", null),
            new FunnelStage("store_page_views", "Store page views", MetricChannel.STORE, "page_views",
                    "Connect App Store Connect", "store_impressions"),
            new FunnelStage("store_downloads", "Downloads", MetricChannel.STORE, "downloads",
                    "Connect App Store Connect", "store_page_views"),
            new FunnelStage("ads_taps", "Ad taps", MetricChannel.ADS, "taps", "Connect an ads account", null),
            new FunnelStage("ads_installs", "Ad installs", MetricChannel.ADS, "installs", "Connect an ads account",
                    "ads_taps"),
            new FunnelStage("product_stages", "In-app stages (first open → paid)", MetricChannel.PRODUCT, "stages",
                    "Connect product metrics", null));

    private MetricCatalog() {
    }

    public static List<Channel> channels() {
        return CHANNELS;
    }

    public static List<FunnelStage> funnel() {
        return FUNNEL;
    }

    /**
     * The funnel with the in-app stages a connected backend declared (its {@code funnelOrder} metrics) placed
     * right after store downloads in place of the placeholder. The first in-app stage converts from downloads,
     * each later one from the stage before it. With no declared stages the placeholder stays.
     */
    public static List<FunnelStage> funnel(Channel productChannel) {
        List<Metric> stages = productChannel.metrics().stream()
                .filter(m -> m.funnelOrder() != null)
                .sorted(java.util.Comparator.comparing(Metric::funnelOrder).thenComparing(Metric::key))
                .toList();
        if (stages.isEmpty()) {
            return FUNNEL;
        }
        List<FunnelStage> out = new java.util.ArrayList<>();
        for (FunnelStage stage : FUNNEL) {
            if (stage.channel() == MetricChannel.PRODUCT) {
                continue;
            }
            out.add(stage);
            if (stage.key().equals("store_downloads")) {
                String previous = stage.key();
                for (Metric m : stages) {
                    String key = "product_" + m.key();
                    out.add(new FunnelStage(key, m.label(), MetricChannel.PRODUCT, m.key(),
                            "No data yet for this in-app stage", previous));
                    previous = key;
                }
            }
        }
        return out;
    }

    /**
     * The PRODUCT channel for a workspace: its metrics are the definitions the connected product backends
     * declared. Funnel stages first (by funnelOrder), then the rest by key. Where several backends declare the
     * same key the first wins and their daily values add up. A RATIO whose parts are missing or are themselves
     * ratios is dropped, so a roll-up can never loop.
     */
    public static Channel productChannel(List<ChannelMetricDefinitionRow> definitions) {
        Channel base = channel(MetricChannel.PRODUCT).orElseThrow();
        java.util.Map<String, ChannelMetricDefinitionRow> byKey = new java.util.LinkedHashMap<>();
        for (ChannelMetricDefinitionRow d : definitions) {
            byKey.putIfAbsent(d.getMetric(), d);
        }
        List<Metric> metrics = new java.util.ArrayList<>();
        List<Column> columns = new java.util.ArrayList<>();
        List<ChannelMetricDefinitionRow> ordered = byKey.values().stream()
                .sorted(java.util.Comparator
                        .comparing((ChannelMetricDefinitionRow d) -> d.getFunnelOrder() == null ? Integer.MAX_VALUE : d.getFunnelOrder())
                        .thenComparing(ChannelMetricDefinitionRow::getMetric))
                .toList();
        for (ChannelMetricDefinitionRow d : ordered) {
            Unit unit = Unit.valueOf(d.getUnit());
            Good good = Good.valueOf(d.getGood());
            if (d.getAggregation().equals("RATIO")) {
                ChannelMetricDefinitionRow n = byKey.get(d.getNumerator());
                ChannelMetricDefinitionRow dn = byKey.get(d.getDenominator());
                if (n == null || dn == null || n.getAggregation().equals("RATIO") || dn.getAggregation().equals("RATIO")) {
                    continue;
                }
                metrics.add(new Metric(d.getMetric(), d.getLabel(), unit, Aggregation.DERIVED, good, false, false,
                        false, d.getNumerator(), d.getDenominator(), d.getFunnelOrder()));
            } else {
                Aggregation agg = d.getAggregation().equals("LEVEL") ? Aggregation.LEVEL : Aggregation.SUM;
                metrics.add(new Metric(d.getMetric(), d.getLabel(), unit, agg, good, false, false, false, null, null,
                        d.getFunnelOrder()));
            }
        }
        // Breakdown tables read SUM metrics (and ratios of two of them) per source and per campaign.
        List<Column> dimensionColumns = new java.util.ArrayList<>();
        for (Metric m : metrics) {
            if (m.agg() == Aggregation.SUM) {
                dimensionColumns.add(sumColumn(m.key(), m.key(), m.label(), m.unit()));
            }
        }
        for (Metric m : metrics) {
            if (m.agg() == Aggregation.DERIVED
                    && dimensionColumns.stream().anyMatch(c -> c.key().equals(m.numerator()))
                    && dimensionColumns.stream().anyMatch(c -> c.key().equals(m.denominator()))) {
                dimensionColumns.add(ratioColumn(m.key(), m.label(), m.unit(), m.numerator(), m.denominator()));
            }
        }
        List<Dimension> dimensions = dimensionColumns.isEmpty() ? List.of() : List.of(
                new Dimension("sources", "By source", dimensionColumns, "source:"),
                new Dimension("campaigns", "By campaign", dimensionColumns, "campaign:"));
        return new Channel(MetricChannel.PRODUCT, base.label(), base.connectorIds(), metrics, dimensions,
                base.headline());
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
