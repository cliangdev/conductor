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
     *
     * <p>WINDOW_UNIQUE is a distinct count that cannot be built from daily counts (visitors: a person who
     * returns on three days is one visitor, not three). Its series is the daily rows stored under the
     * metric's own key, but its window value is the stored {@code <key>_unique_<N>d} row on the window's last
     * day, N being the window's length in days (7, 28 or 90). The previous window's value is the same row on
     * the previous window's last day. A window of any other length, or a last day with no such row, has no
     * value (null): it is never rebuilt by summing the daily rows. Connections add up, like every other flow.
     */
    public enum Aggregation { SUM, LEVEL, DERIVED, WINDOW_UNIQUE }

    /** Which way a move reads as good news; also the default direction of a target. */
    public enum Good { UP, DOWN }

    /**
     * Where a metric's number comes from and what it counts, in one place that people, MCP tools, the
     * report and the benchmarks all read. {@code numerator} and {@code denominator} are words of the closed
     * {@link #MEASURES} vocabulary ({@code denominator} is null for a plain count); two metrics are comparable
     * only when numerator, denominator and source type all match.
     *
     * @param source     one plain line naming the platform report or field the number comes from
     * @param sourceType {@code ALL}, or one App Store traffic source type (APP_STORE_SEARCH ...); null means ALL
     * @param exclusions one plain line naming what is left out of the number, or null when nothing is
     * @param timeZone   the time zone that decides which day a row belongs to, or null when the source does not say
     */
    public record Definition(String source, String numerator, String denominator, String sourceType,
                             String exclusions, String timeZone) {

        /** The source type with the null default spelled out. */
        public String sourceTypeOrAll() {
            return sourceType == null ? "ALL" : sourceType;
        }

        Definition withSourceType(String type) {
            return new Definition(source, numerator, denominator, type, exclusions, timeZone);
        }

        Definition withExclusions(String text) {
            return new Definition(source, numerator, denominator, sourceType, text, timeZone);
        }

        Definition withTimeZone(String zone) {
            return new Definition(source, numerator, denominator, sourceType, exclusions, zone);
        }
    }

    /**
     * The measures a {@link Definition} may name as numerator or denominator, plus {@code product:<key>} for a
     * metric a product feed declared (see {@link #isMeasure}).
     */
    public static final List<String> MEASURES = List.of(
            "impressions", "unique_impressions", "page_views", "unique_page_views", "first_time_downloads",
            "redownloads", "total_downloads", "downloads_of_record", "tap_installs", "tap_new_downloads",
            "view_through_installs", "taps", "spend", "visitors", "sessions", "pageviews", "conversion_sessions",
            "conversion_events", "clicks", "followers", "posts", "likes", "channel_views", "website_clicks",
            "rating_count", "rating_avg", "position");

    /** The prefix of a measure that names a product feed's own metric. */
    public static final String PRODUCT_MEASURE_PREFIX = "product:";

    /** True for a word of {@link #MEASURES} or for {@code product:<key>} with a non-blank key. */
    public static boolean isMeasure(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (name.startsWith(PRODUCT_MEASURE_PREFIX)) {
            return !name.substring(PRODUCT_MEASURE_PREFIX.length()).isBlank();
        }
        return MEASURES.contains(name);
    }

    /** The source type that stands for every traffic source. */
    public static final String ALL_SOURCES = "ALL";

    /**
     * @param hidden         internal building block (for example position times impressions), never output
     * @param optional       omitted from output until the channel has reported at least one row for it
     * @param fromDimensions the rows carry their series in {@code dimension_key} (a platform), so the metric
     *                       is the sum across all dimension keys instead of the '' total row
     * @param funnelOrder    position as a stage of the in-app funnel (a PRODUCT metric a feed declared), else null
     * @param basis          EVENT, or COHORT when each day's value counts the people who first opened that day (PRODUCT)
     * @param area           ACQUISITION, ACTIVATION, RETENTION or REVENUE when a feed grouped it (PRODUCT), else null
     * @param description    what the metric counts, in the feed's words (PRODUCT), else null
     * @param headline       priority among the feed's headline metrics, 1 first (PRODUCT), else null
     * @param watchBandPct   how close a miss counts as WATCH, in percent of the target; null means the default
     * @param storedKey      the stored metric this one reads when that is not its own key, else null
     * @param dimensionKey   with {@code storedKey} (or alone): the one dimension key whose rows this metric reads
     *                       (for example {@code source:APP_STORE_SEARCH}) instead of the '' total, else null
     * @param definition     where the number comes from and what it counts; every catalog metric has one
     */
    public record Metric(String key, String label, Unit unit, Aggregation agg, Good good, boolean hidden,
                         boolean optional, boolean fromDimensions, String numerator, String denominator,
                         Integer funnelOrder, String basis, String area, String description, Integer headline,
                         Double watchBandPct, String storedKey, String dimensionKey, Definition definition) {
        public Metric(String key, String label, Unit unit, Aggregation agg, Good good, boolean hidden,
                      boolean optional, boolean fromDimensions, String numerator, String denominator,
                      Integer funnelOrder, String basis, String area, String description, Integer headline,
                      Double watchBandPct) {
            this(key, label, unit, agg, good, hidden, optional, fromDimensions, numerator, denominator, funnelOrder,
                    basis, area, description, headline, watchBandPct, null, null, null);
        }

        public Metric(String key, String label, Unit unit, Aggregation agg, Good good, boolean hidden,
                      boolean optional, boolean fromDimensions, String numerator, String denominator,
                      Integer funnelOrder) {
            this(key, label, unit, agg, good, hidden, optional, fromDimensions, numerator, denominator, funnelOrder,
                    "EVENT", null, null, null, null);
        }

        public boolean cohort() {
            return "COHORT".equals(basis);
        }

        /** The stored metric whose rows this one reads: its own key unless {@code storedKey} points elsewhere. */
        public String stored() {
            return storedKey != null ? storedKey : key;
        }

        Metric withDefinition(Definition d) {
            return new Metric(key, label, unit, agg, good, hidden, optional, fromDimensions, numerator, denominator,
                    funnelOrder, basis, area, description, headline, watchBandPct, storedKey, dimensionKey, d);
        }
    }

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

        /** True when any metric reads dimension rows (a platform series, or one source type's rows), so they must be loaded. */
        public boolean usesDimensionSeries() {
            return metrics.stream().anyMatch(m -> m.fromDimensions() || m.dimensionKey() != null);
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
            "tiktok", "TikTok",
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

    /** The same metric, grouped under a report area (ACQUISITION, ACTIVATION, RETENTION or REVENUE). */
    private static Metric inArea(Metric m, String area) {
        return new Metric(m.key(), m.label(), m.unit(), m.agg(), m.good(), m.hidden(), m.optional(),
                m.fromDimensions(), m.numerator(), m.denominator(), m.funnelOrder(), m.basis(), area,
                m.description(), m.headline(), m.watchBandPct(), m.storedKey(), m.dimensionKey(), m.definition());
    }

    private static Metric defined(Metric m, Definition definition) {
        return m.withDefinition(definition);
    }

    private static Metric hiddenSum(String key) {
        return new Metric(key, key, Unit.COUNT, Aggregation.SUM, Good.UP, true, false, false, null, null, null);
    }

    /** A distinct count whose window value is a stored per-window row, not a sum of days (see {@link Aggregation#WINDOW_UNIQUE}). */
    private static Metric windowUnique(String key, String label, Good good) {
        return new Metric(key, label, Unit.COUNT, Aggregation.WINDOW_UNIQUE, good, false, false, false, null, null,
                null);
    }

    /**
     * A hidden building block that sums one stored metric at ONE dimension key (for example total downloads of
     * the App Store search source), so a ratio can be taken within a single source.
     */
    private static Metric hiddenSumAt(String key, String storedKey, String dimensionKey) {
        return new Metric(key, key, Unit.COUNT, Aggregation.SUM, Good.UP, true, false, false, null, null, null,
                "EVENT", null, null, null, null, storedKey, dimensionKey, null);
    }

    private static Metric optionalDerived(String key, String label, Unit unit, Good good, String numerator,
                                          String denominator) {
        return new Metric(key, label, unit, Aggregation.DERIVED, good, false, true, false, numerator, denominator,
                null);
    }

    private static Definition count(String source, String measure) {
        return new Definition(source, measure, null, null, null, null);
    }

    private static Definition ratio(String source, String numerator, String denominator) {
        return new Definition(source, numerator, denominator, null, null, null);
    }

    private static List<Metric> append(List<Metric> base, List<Metric> more) {
        List<Metric> all = new java.util.ArrayList<>(base);
        all.addAll(more);
        return List.copyOf(all);
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
        return new Metric(key, label, unit, Aggregation.LEVEL, Good.UP, false, true, true, null, weightKey, null);
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

    /** The dimension-key prefix of the Apple Ads search-term rows ({@code searchterm:{campaignId}:{term}}). */
    public static final String SEARCH_TERM_PREFIX = "searchterm:";

    private static final String WEB_TIME_ZONE = "PostHog project time zone";
    private static final String WEB_EXCLUSIONS =
            "Internal-users cohort and excluded share-page paths, when set on the connection";
    private static final String STORE_ANALYTICS = "App Store Connect Analytics";
    private static final String ADS_REPORT = "Apple Ads campaign report";

    private static final List<Channel> CHANNELS = List.of(
            new Channel(MetricChannel.WEB, "Web", List.of("posthog"),
                    List.of(
                            defined(windowUnique("visitors", "Visitors", Good.UP),
                                    webCount("PostHog: distinct persons with a page view in the window", "visitors")),
                            defined(sum("sessions", "Sessions", Unit.COUNT, Good.UP),
                                    webCount("PostHog: distinct sessions across all events", "sessions")),
                            defined(sum("pageviews", "Page views", Unit.COUNT, Good.UP),
                                    webCount("PostHog: count of page view events", "pageviews")),
                            defined(optionalSum("conversion_clicks", "Conversion clicks", Unit.COUNT, Good.UP, false),
                                    webCount("PostHog: count of the configured conversion event", "conversion_events")),
                            defined(optionalSum("conversion_sessions", "Conversion sessions", Unit.COUNT, Good.UP,
                                            false),
                                    webCount("PostHog: distinct sessions with the configured conversion event",
                                            "conversion_sessions"))),
                    List.of(new Dimension("referrers", "Top referrers",
                            List.of(sumColumn("sessions", "sessions_by_referrer", "Sessions", Unit.COUNT)))),
                    4),
            new Channel(MetricChannel.SEARCH, "Search", List.of("gsc"),
                    List.of(
                            defined(sum("clicks", "Clicks", Unit.COUNT, Good.UP),
                                    count("Search Console: clicks on search results", "clicks")),
                            defined(sum("impressions", "Impressions", Unit.COUNT, Good.UP),
                                    count("Search Console: search result impressions", "impressions")),
                            defined(derived("ctr", "Click-through rate", Unit.PERCENT, Good.UP, "clicks", "impressions"),
                                    ratio("Search Console: clicks divided by impressions over the window", "clicks",
                                            "impressions")),
                            defined(derived("position", "Average position", Unit.POSITION, Good.DOWN,
                                            "position_x_impressions", "impressions"),
                                    count("Search Console: average position, weighted by impressions", "position")),
                            defined(hiddenSum("position_x_impressions"),
                                    count("Search Console: position multiplied by impressions, summed "
                                            + "(building block of average position)", "position"))),
                    List.of(new Dimension("queries", "Top queries", List.of(
                            sumColumn("clicks", "query_clicks", "Clicks", Unit.COUNT),
                            sumColumn("impressions", "query_impressions", "Impressions", Unit.COUNT),
                            ratioColumn("ctr", "Click-through rate", Unit.PERCENT, "clicks", "impressions")))),
                    4),
            // TikTok's account stats (followers, video count, likes) are written only when the server flag
            // enables the user.info.stats scope and the connection's granted scopes include it.
            new Channel(MetricChannel.SOCIAL, "Social", List.of("meta", "youtube", "tiktok"),
                    List.of(
                            defined(level("followers", "Followers", false),
                                    count("Meta, YouTube and TikTok: followers or subscribers on the latest reading",
                                            "followers")),
                            defined(level("media_count", "Posts", true),
                                    count("Meta, YouTube and TikTok: posts or videos on the account, latest reading",
                                            "posts")),
                            defined(level("channel_views", "Channel views", true),
                                    count("YouTube: lifetime channel views, latest reading", "channel_views")),
                            defined(optionalSum("website_clicks", "Website clicks", Unit.COUNT, Good.UP, true),
                                    count("Meta: taps on the profile's website link", "website_clicks")),
                            defined(level("likes", "Likes", true),
                                    count("TikTok: total likes the account has received, latest reading", "likes"))),
                    List.of(new Dimension("platforms", "By platform", List.of(
                            levelColumn("followers", "Followers"),
                            levelColumn("media_count", "Posts"),
                            levelColumn("channel_views", "Channel views"),
                            sumColumn("website_clicks", "website_clicks", "Website clicks", Unit.COUNT),
                            levelColumn("likes", "Likes")))),
                    4),
            new Channel(MetricChannel.STORE, "App Store", List.of("app-store-connect"),
                    append(List.of(
                            defined(sum("impressions", "Impressions", Unit.COUNT, Good.UP),
                                    storeCount(STORE_ANALYTICS + ": product page impressions", "impressions")),
                            defined(sum("page_views", "Page views", Unit.COUNT, Good.UP),
                                    storeCount(STORE_ANALYTICS + ": product page views", "page_views")),
                            defined(inArea(sum("downloads", "Downloads", Unit.COUNT, Good.UP), "ACQUISITION"),
                                    storeCount(STORE_ANALYTICS + ": first-time downloads (Apple applies a privacy "
                                            + "threshold)", "first_time_downloads")),
                            // Apple's definition: first-time downloads over unique impressions
                            defined(inArea(derived("conversion_rate", "Conversion rate", Unit.PERCENT, Good.UP,
                                            "downloads", "impressions_unique"), "ACQUISITION"),
                                    ratio(STORE_ANALYTICS + ": first-time downloads over unique impressions, all "
                                            + "traffic sources", "first_time_downloads", "unique_impressions")
                                            .withSourceType(ALL_SOURCES)),
                            defined(optionalSum("redownloads", "Redownloads", Unit.COUNT, Good.UP, false),
                                    storeCount(STORE_ANALYTICS + ": redownloads", "redownloads")),
                            defined(inArea(ratingLevel("rating_count", "Ratings", Unit.COUNT), "ACQUISITION"),
                                    count("App Store Connect: number of ratings on the latest reading",
                                            "rating_count")),
                            defined(weightedRatingLevel("rating_avg", "Average rating", Unit.RATING, "rating_count"),
                                    count("App Store Connect: average rating on the latest reading, weighted by "
                                            + "rating count across apps", "rating_avg")),
                            defined(hiddenSum("impressions_unique"),
                                    storeCount(STORE_ANALYTICS + ": unique product page impressions",
                                            "unique_impressions")),
                            defined(hiddenSum("total_downloads"),
                                    storeCount(STORE_ANALYTICS + ": first-time downloads plus redownloads",
                                            "total_downloads")),
                            defined(optionalSum("downloads_of_record", "Downloads of record", Unit.COUNT, Good.UP, false),
                                    storeCount("App Store Connect Sales Reports, first-time downloads",
                                            "downloads_of_record")),
                            defined(hiddenSum("sales_report_days"),
                                    storeCount("App Store Connect Sales Reports: 1 on each day a report file was "
                                            + "read (coverage of downloads of record)", "downloads_of_record"))),
                            perSourceConversion()),
                    List.of(new Dimension("sources", "By traffic source", List.of(
                            sumColumn("impressions", "impressions", "Impressions", Unit.COUNT),
                            sumColumn("page_views", "page_views", "Page views", Unit.COUNT),
                            sumColumn("downloads", "downloads", "Downloads", Unit.COUNT),
                            ratioColumn("download_rate", "Downloads per page view", Unit.PERCENT,
                                    "downloads", "page_views")))),
                    3),
            new Channel(MetricChannel.ADS, "Ads", List.of("apple-search-ads"),
                    List.of(
                            defined(sum("spend", "Spend", Unit.CURRENCY, Good.DOWN),
                                    adsCount(ADS_REPORT + ", localSpend", "spend")),
                            defined(sum("installs", "Installs", Unit.COUNT, Good.UP),
                                    adsCount(ADS_REPORT + ", tapInstalls (tap new downloads + tap redownloads)",
                                            "tap_installs")),
                            defined(inArea(derived("cost_per_install", "Cost per install", Unit.CURRENCY, Good.DOWN,
                                            "spend", "installs"), "ACQUISITION"),
                                    adsRatio("Apple Ads: spend divided by tap installs", "spend", "tap_installs")),
                            defined(sum("taps", "Taps", Unit.COUNT, Good.UP),
                                    adsCount(ADS_REPORT + ", taps", "taps")),
                            defined(sum("impressions", "Impressions", Unit.COUNT, Good.UP),
                                    adsCount(ADS_REPORT + ", impressions", "impressions")),
                            defined(derived("tap_through_rate", "Tap-through rate", Unit.PERCENT, Good.UP,
                                            "taps", "impressions"),
                                    adsRatio("Apple Ads: taps divided by impressions", "taps", "impressions")),
                            defined(optionalSum("view_installs", "View-through installs", Unit.COUNT, Good.UP, false),
                                    adsCount(ADS_REPORT + ", viewInstalls (installs with no tap; never added to tap "
                                            + "installs)", "view_through_installs")),
                            defined(optionalSum("new_downloads", "First-time downloads", Unit.COUNT, Good.UP, false),
                                    adsCount(ADS_REPORT + ", tapNewDownloads (first-time downloads from taps)",
                                            "tap_new_downloads"))),
                    List.of(new Dimension("campaigns", "Campaigns", List.of(
                                    sumColumn("spend", "spend", "Spend", Unit.CURRENCY),
                                    sumColumn("impressions", "impressions", "Impressions", Unit.COUNT),
                                    sumColumn("taps", "taps", "Taps", Unit.COUNT),
                                    sumColumn("installs", "installs", "Installs", Unit.COUNT),
                                    ratioColumn("cost_per_install", "Cost per install", Unit.CURRENCY, "spend", "installs")),
                                    "campaign:"),
                            new Dimension("keywords", "Keywords", adBreakdownColumns(), "keyword:"),
                            new Dimension("ads", "Ads", adBreakdownColumns(), "ad:"),
                            new Dimension("searchterms", "Search terms", List.of(
                                    sumColumn("impressions", "impressions", "Impressions", Unit.COUNT),
                                    sumColumn("taps", "taps", "Taps", Unit.COUNT),
                                    sumColumn("installs", "installs", "Installs", Unit.COUNT),
                                    sumColumn("spend", "spend", "Spend", Unit.CURRENCY),
                                    ratioColumn("cost_per_install", "Cost per install", Unit.CURRENCY, "spend",
                                            "installs")),
                                    SEARCH_TERM_PREFIX)),
                    4),
            // The in-app channel has no fixed metrics: each connected backend declares its own (see productChannel).
            new Channel(MetricChannel.PRODUCT, "In-app", List.of(PRODUCT_CONNECTOR_ID), List.of(), List.of(), 4));

    private static Definition webCount(String source, String measure) {
        return count(source, measure).withExclusions(WEB_EXCLUSIONS).withTimeZone(WEB_TIME_ZONE);
    }

    private static Definition storeCount(String source, String measure) {
        return count(source, measure).withSourceType(ALL_SOURCES);
    }

    private static Definition adsCount(String source, String measure) {
        return count(source, measure).withTimeZone("UTC");
    }

    private static Definition adsRatio(String source, String numerator, String denominator) {
        return ratio(source, numerator, denominator).withTimeZone("UTC");
    }

    /**
     * Per traffic source, Apple's App Analytics conversion: total downloads (first-time plus redownloads) over unique
     * impressions of that one source type. Each rate reads two hidden stored-metric-at-one-dimension helpers; the rate
     * is shown only once the source has rows for both.
     */
    private static List<Metric> perSourceConversion() {
        String[][] sources = {
                {"search", "APP_STORE_SEARCH", "Search"},
                {"browse", "APP_STORE_BROWSE", "Browse"},
                {"referrer", "APP_REFERRER", "App referrer"},
                {"web", "WEB_REFERRER", "Web referrer"}};
        List<Metric> out = new java.util.ArrayList<>();
        for (String[] s : sources) {
            String type = s[1];
            String dimension = STORE_SOURCE_PREFIX + type;
            String where = storeSourceLabel(type);
            String downloads = s[0] + "_total_downloads";
            String impressions = s[0] + "_impressions_unique";
            out.add(defined(hiddenSumAt(downloads, "total_downloads", dimension),
                    count(STORE_ANALYTICS + ": first-time downloads plus redownloads, " + where + " only",
                            "total_downloads").withSourceType(type)));
            out.add(defined(hiddenSumAt(impressions, "impressions_unique", dimension),
                    count(STORE_ANALYTICS + ": unique product page impressions, " + where + " only",
                            "unique_impressions").withSourceType(type)));
            out.add(defined(optionalDerived("conversion_rate_" + s[0], s[2] + " conversion rate", Unit.PERCENT,
                            Good.UP, downloads, impressions),
                    ratio(STORE_ANALYTICS + ": total downloads over unique impressions, " + where
                            + " only (Apple's App Analytics definition)", "total_downloads", "unique_impressions")
                            .withSourceType(type)));
        }
        return out;
    }

    /**
     * Keyword and ad tables: delivery, the two funnel rates, spend and cost per install. Installs are tap installs,
     * as on Campaigns; first-time downloads (tap new downloads) have a column of their own.
     */
    private static List<Column> adBreakdownColumns() {
        return List.of(
                sumColumn("impressions", "impressions", "Impressions", Unit.COUNT),
                sumColumn("taps", "taps", "Taps", Unit.COUNT),
                ratioColumn("tap_through_rate", "Tap rate", Unit.PERCENT, "taps", "impressions"),
                sumColumn("installs", "installs", "Installs", Unit.COUNT),
                sumColumn("new_downloads", "new_downloads", "First-time downloads", Unit.COUNT),
                ratioColumn("install_rate", "Install rate", Unit.PERCENT, "installs", "taps"),
                sumColumn("spend", "spend", "Spend", Unit.CURRENCY),
                ratioColumn("cost_per_install", "Cost per install", Unit.CURRENCY, "spend", "installs"));
    }

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
     *
     * <p>Each metric's {@link Definition} names its feed metric ({@code product:<key>}), says the number comes from
     * the product metrics feed of the connection that declared it, and carries the feed's own exclusions.
     */
    public static Channel productChannel(List<ChannelMetricDefinitionRow> definitions) {
        return productChannel(definitions, java.util.Map.of());
    }

    /**
     * As {@link #productChannel(List)}, naming each metric's feed by its connection's label ({@code connectionLabels}
     * by connection id); a connection without a label reads as "product backend".
     */
    public static Channel productChannel(List<ChannelMetricDefinitionRow> definitions,
                                         java.util.Map<String, String> connectionLabels) {
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
                        false, d.getNumerator(), d.getDenominator(), d.getFunnelOrder(), d.getBasis(), d.getArea(),
                        d.getDescription(), d.getHeadline(), watchBand(d), null, null,
                        productDefinition(d, PRODUCT_MEASURE_PREFIX + d.getNumerator(),
                                PRODUCT_MEASURE_PREFIX + d.getDenominator(), connectionLabels)));
            } else {
                Aggregation agg = d.getAggregation().equals("LEVEL") ? Aggregation.LEVEL : Aggregation.SUM;
                metrics.add(new Metric(d.getMetric(), d.getLabel(), unit, agg, good, false, false, false, null, null,
                        d.getFunnelOrder(), d.getBasis(), d.getArea(), d.getDescription(), d.getHeadline(),
                        watchBand(d), null, null,
                        productDefinition(d, PRODUCT_MEASURE_PREFIX + d.getMetric(), null, connectionLabels)));
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
                new Dimension("campaigns", "By campaign", dimensionColumns, "campaign:"),
                new Dimension("keywords", "By keyword", dimensionColumns, "keyword:"));
        return new Channel(MetricChannel.PRODUCT, base.label(), base.connectorIds(), metrics, dimensions,
                base.headline());
    }

    private static Definition productDefinition(ChannelMetricDefinitionRow d, String numerator, String denominator,
                                                java.util.Map<String, String> connectionLabels) {
        String label = connectionLabels.get(d.getConnectionId());
        String feed = label == null || label.isBlank() ? "product backend" : label;
        String exclusions = d.getExclusions() == null || d.getExclusions().isBlank() ? null : d.getExclusions().strip();
        return new Definition("Product metrics feed (" + feed + ")", numerator, denominator, null, exclusions, null);
    }

    private static Double watchBand(ChannelMetricDefinitionRow d) {
        return d.getWatchBandPct() == null ? null : d.getWatchBandPct().doubleValue();
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
