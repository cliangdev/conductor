package com.conductor.marketing.overview;

import com.conductor.entity.ChannelMetricDaily;
import com.conductor.entity.ChannelMetricDefinitionRow;
import com.conductor.entity.ChannelMetricTarget;
import com.conductor.entity.Connection;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.integration.Direction;
import com.conductor.integration.DigestSpec;
import com.conductor.integration.MetricChannel;
import com.conductor.integration.MetricSpec;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
import com.conductor.integration.ingest.digest.MetricsBaseline;
import com.conductor.integration.ingest.digest.MetricsChangeDetector;
import com.conductor.integration.ingest.digest.MetricsSnapshot;
import com.conductor.marketing.insights.MarketingInsightsQueryService;
import com.conductor.marketing.overview.MetricCatalog.Aggregation;
import com.conductor.marketing.overview.MetricCatalog.Column;
import com.conductor.integration.ingest.ChannelDimensionStateStore;
import com.conductor.marketing.overview.MetricCatalog.Dimension;
import com.conductor.marketing.overview.MetricCatalog.Metric;
import com.conductor.marketing.overview.MetricCatalog.Unit;
import com.conductor.marketing.promises.WilsonInterval;
import com.conductor.marketing.verification.MarketingPlatformRead;
import com.conductor.marketing.verification.MarketingPlatformReadRepository;
import com.conductor.marketing.verification.ReconciliationBand;
import com.conductor.marketing.verification.ReferencePairs;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ChannelMetricDefinitionRepository;
import com.conductor.repository.ChannelMetricTargetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.publish.PublishPlatformRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes the marketing overview: the funnel, every channel's freshness, and each metric's value, change
 * against the previous period, noise flag, target and daily series. Reads {@code channel_metric_daily}
 * (written by the {@code channel_metrics} feeds) and knows how to read it only through {@link MetricCatalog}.
 *
 * <p><b>Windows.</b> A window is a run of full UTC days ending at the channel's {@code dataCompleteThrough}
 * (inclusive): the latest day with non-provisional data on every connection, never later than
 * <em>yesterday</em>, or yesterday when there is none. The previous window is the same number of days
 * immediately before it. Days after it are shown in the series as provisional but never counted. Today is left out on purpose: it is partial on
 * every platform, and comparing 14 hours of today against 24 hours of a day earlier would read as a slump.
 * This matches how the platform dashboards report finished days. Days inside a source's restatement window
 * carry {@code provisional} and are counted in {@code provisionalDays}.
 *
 * <p><b>Aggregation.</b> Several connections feeding one channel are summed. Derived metrics divide the
 * rolled-up components, so click-through rate and position are weighted by impressions rather than an
 * average of daily ratios. A distinct count (web visitors) is the one exception to "windows add up days": its window
 * value is the stored count for exactly that window length on the window's last day, or null with a note, never a
 * sum of daily counts ({@link MetricCatalog.Aggregation#WINDOW_UNIQUE}).
 *
 * <p><b>Definitions.</b> Every metric view carries its {@link MetricCatalog.Definition} (source, numerator,
 * denominator, source type, exclusions, time zone), and {@link #definitions} lists them for the workspace.
 *
 * <p><b>Noise.</b> {@link MetricsChangeDetector} judges each metric: the windows before the current one are
 * replayed through it as consecutive periods to build its baseline, then the current window is checked
 * against it with the detector's own gates (absolute, relative, statistical) and defaults. A change that
 * does not clear them is {@code noise}.
 *
 * <p><b>Fixed windows.</b> A window may instead be named by its days, {@code YYYY-MM-DD..YYYY-MM-DD}: first day
 * not after the last, the last no later than yesterday (UTC), at most {@link #FIXED_WINDOW_MAX_DAYS} days. Every
 * channel then reads exactly those days, not a window ending at its own {@code dataCompleteThrough}; the previous
 * window is the same number of days immediately before. A channel whose settled data ends before the last day
 * counts only the days it has settled (days still being restated are never counted, as in a rolling window),
 * reports {@code partial}, and withholds the previous-window comparison of its flows: a short window set against
 * a full one is no comparison.
 *
 * <p><b>Reconciliation.</b> Each channel metric the product backend also reports (see {@link ReferencePairs}) is
 * compared with the backend's reference over the settled days both have, judged by {@link ReconciliationBand}; a
 * direct platform read recorded in the verification ledger is compared with the channel's own figure over the
 * read's days. Each disagreement is a {@link MismatchView} in {@link ChannelView#mismatches()} and the channel
 * reads MISMATCH (unless it is lagging, which wins).
 */
@Service
public class MarketingOverviewQueryService {

    /** A feed that has not succeeded in this many of its own intervals is stale. */
    static final int STALE_INTERVALS = 2;
    static final int DIMENSION_ROWS = 50;
    static final int TOP_POSTS = 5;
    /** A feed-reported sample below this reads as insufficient data (and so as noise). */
    static final long MIN_SAMPLE = 20;
    /** How close a miss counts as WATCH rather than OFF_TRACK, in percent of the target, when a target does not say. */
    static final double DEFAULT_WATCH_BAND_PCT = 10;
    /** The period a count or currency total target is stated for when it does not say. */
    static final int DEFAULT_TARGET_WINDOW_DAYS = 28;
    /** The most days a fixed window may span. */
    public static final int FIXED_WINDOW_MAX_DAYS = 92;
    private static final Pattern FIXED_WINDOW = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\.\\.(\\d{4}-\\d{2}-\\d{2})$");
    /** The window a request gets when it names none. */
    static final String DEFAULT_WINDOW = "28d";
    /** The rolling lengths the overview and the report accept; the channel detail also takes 90. */
    static final Set<Integer> ROLLING_OVERVIEW_DAYS = Set.of(7, 28);
    static final Set<Integer> ROLLING_CHANNEL_DAYS = Set.of(7, 28, 90);
    /**
     * A channel whose data ends more than this many days before the freshest channel's is lagging: it is marked
     * stale and no longer holds the funnel window back. Well above the normal 2-3 day lag of Search Console or
     * the App Store.
     */
    public static final int LAGGING_DAYS = 7;
    /** How many earlier windows feed the noise baseline, bounded by {@link #MAX_HISTORY_DAYS}. */
    private static final int HISTORY_PERIODS = 5;
    private static final int MAX_HISTORY_DAYS = 180;

    /** {@code to} and {@code dataCompleteThroughMin} are null on an overview with no data in any channel. */
    public record Window(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                         LocalDate dataCompleteThroughMin) {}

    public record ConnectorRef(String connectorId, String name) {}

    public record ConnectHint(String connectorId, List<ConnectorRef> connectors) {}

    public record ConnectionView(String id, String connectorId, String name, String status) {}

    public record Point(LocalDate day, Double value, boolean provisional) {}

    /** {@code readOnly} targets come from a connector feed (the product backend sets them); {@code source} says where. */
    public record TargetView(double value, String direction, boolean readOnly, String source, String window,
                             Double watchBandPct, Double effectiveValue) {
        public TargetView(double value, String direction, boolean readOnly, String source, String window,
                          Double watchBandPct) {
            this(value, direction, readOnly, source, window, watchBandPct, null);
        }

        public TargetView(double value, String direction, boolean readOnly, String source) {
            this(value, direction, readOnly, source, null, null, null);
        }

        TargetView withEffectiveValue(double effective) {
            return new TargetView(value, direction, readOnly, source, window, watchBandPct, effective);
        }

        public TargetView(double value, String direction) {
            this(value, direction, false, null);
        }
    }

    // A metric's standing against its target (see targetStatus).
    public static final String ON_TRACK = "ON_TRACK";
    public static final String WATCH = "WATCH";
    public static final String OFF_TRACK = "OFF_TRACK";
    public static final String TOO_EARLY = "TOO_EARLY";
    /** A targeted snapshot the feed stopped sending (see {@link #stoppedArriving}): not judged. */
    public static final String STOPPED = "STOPPED";
    public static final String NO_TARGET = "NO_TARGET";

    /**
     * ON_TRACK, WATCH, OFF_TRACK, TOO_EARLY, STOPPED or NO_TARGET; {@code missPct} is the shortfall as a fraction of
     * the target. {@code closeToTarget} is true when the target is met or missed by no more than half the watch
     * band: an open off-track alert resolves only then, so a value hovering at the band's edge, or inside the
     * Wilson interval only by chance, does not resolve and re-raise as the window rolls.
     */
    public record TargetStatus(String status, Double missPct, boolean closeToTarget) {
        public TargetStatus(String status, Double missPct) {
            this(status, missPct, ON_TRACK.equals(status));
        }
    }

    /** {@code insufficientData}: the feed reported fewer than {@link #MIN_SAMPLE} observations behind the window.
     *  {@code funnelOrder}: the metric's place among the in-app funnel stages (PRODUCT only), else null.
     *  {@code definition}: where the number comes from and what it counts (every catalog metric has one).
     *  {@code note}: a plain sentence about why the value reads as it does: the stored data cannot answer (see
     *  {@link Aggregation#WINDOW_UNIQUE}), or the snapshot stopped arriving (see {@link #stoppedArriving}); else null.
     *  Internal, read by the alerts and not part of the API: {@code closeToTarget} (see
     *  {@link TargetStatus#closeToTarget()}) and {@code lastReportedOn}, the last day a STOPPED metric was reported. */
    public record MetricView(String metric, String label, Unit unit, String goodDirection, Double value, Double previous, Double change,
                             Double changePct, boolean noise, int provisionalDays, TargetView target,
                             List<Point> series, LocalDate historyStartsOn, LocalDate asOf,
                             boolean insufficientData, Integer funnelOrder, String aggregation, String status,
                             Double missPct, Long sampleSize, String area, String description, Integer headline,
                             String basis, MetricCatalog.Definition definition, String note,
                             boolean closeToTarget, LocalDate lastReportedOn) {
        public MetricView(String metric, String label, Unit unit, String goodDirection, Double value, Double previous,
                          Double change, Double changePct, boolean noise, int provisionalDays, TargetView target,
                          List<Point> series, LocalDate historyStartsOn, LocalDate asOf, boolean insufficientData,
                          Integer funnelOrder, String aggregation, String status, Double missPct, Long sampleSize,
                          String area, String description, Integer headline, String basis,
                          MetricCatalog.Definition definition, String note) {
            this(metric, label, unit, goodDirection, value, previous, change, changePct, noise, provisionalDays,
                    target, series, historyStartsOn, asOf, insufficientData, funnelOrder, aggregation, status,
                    missPct, sampleSize, area, description, headline, basis, definition, note,
                    ON_TRACK.equals(status), null);
        }

        public MetricView(String metric, String label, Unit unit, String goodDirection, Double value, Double previous,
                          Double change, Double changePct, boolean noise, int provisionalDays, TargetView target,
                          List<Point> series, LocalDate historyStartsOn, LocalDate asOf, boolean insufficientData,
                          Integer funnelOrder, String aggregation, String status, Double missPct, Long sampleSize,
                          String area, String description, Integer headline, String basis) {
            this(metric, label, unit, goodDirection, value, previous, change, changePct, noise, provisionalDays,
                    target, series, historyStartsOn, asOf, insufficientData, funnelOrder, aggregation, status,
                    missPct, sampleSize, area, description, headline, basis, null, null);
        }

        public MetricView(String metric, String label, Unit unit, String goodDirection, Double value, Double previous,
                          Double change, Double changePct, boolean noise, int provisionalDays, TargetView target,
                          List<Point> series, LocalDate historyStartsOn, LocalDate asOf, boolean insufficientData,
                          Integer funnelOrder, String aggregation) {
            this(metric, label, unit, goodDirection, value, previous, change, changePct, noise, provisionalDays,
                    target, series, historyStartsOn, asOf, insufficientData, funnelOrder, aggregation,
                    null, null, null, null, null, null, "EVENT");
        }

        public MetricView(String metric, String label, Unit unit, String goodDirection, Double value,
                          Double previous, Double change, Double changePct, boolean noise, int provisionalDays,
                          TargetView target, List<Point> series, LocalDate historyStartsOn, LocalDate asOf,
                          boolean insufficientData) {
            this(metric, label, unit, goodDirection, value, previous, change, changePct, noise, provisionalDays,
                    target, series, historyStartsOn, asOf, insufficientData, null, "SUM");
        }

        public MetricView(String metric, String label, Unit unit, String goodDirection, Double value,
                          Double previous, Double change, Double changePct, boolean noise, int provisionalDays,
                          TargetView target, List<Point> series, LocalDate historyStartsOn, LocalDate asOf) {
            this(metric, label, unit, goodDirection, value, previous, change, changePct, noise, provisionalDays,
                    target, series, historyStartsOn, asOf, false, null, "SUM");
        }
    }

    public static final String KIND_BACKEND = "BACKEND";
    public static final String KIND_PLATFORM = "PLATFORM";

    /**
     * A second source's figure against the channel's own connector, over the same settled days
     * ({@code from}..{@code to}). {@code kind} is {@link #KIND_BACKEND} when the second figure is the product
     * backend's reference ({@code referenceSource} names its connection) and {@link #KIND_PLATFORM} when it is a
     * direct platform read ({@code referenceSource} is the name the read was recorded under); {@code gapPct} is
     * the difference as a fraction of the larger figure.
     */
    public record MismatchView(String metric, String label, double referenceValue, double channelValue,
                               double gapPct, LocalDate from, LocalDate to,
                               String channelSource, String referenceSource, String kind) {
        public MismatchView(String metric, String label, double referenceValue, double channelValue,
                            double gapPct, LocalDate from, LocalDate to, String channelSource,
                            String referenceSource) {
            this(metric, label, referenceValue, channelValue, gapPct, from, to, channelSource, referenceSource,
                    KIND_BACKEND);
        }
    }

    /**
     * {@code mismatch} is the entry of {@code mismatches} with the largest gap (a backend one when there is any),
     * for clients that read one; {@code mismatches} lists every second source that disagrees, largest gap first.
     * {@code partial} is true only in a fixed window, when the channel's settled data ends before the window's
     * last day (null otherwise: a rolling window always ends where the data does). {@code noDataYet} is true only
     * for a connected channel with no complete day of data, ever: its {@code dataCompleteThrough} is then the
     * stand-in end of its window, not a day it has data for (null otherwise).
     */
    public record ChannelView(String channel, String label, String status, OffsetDateTime lastFetchedAt,
                              List<ConnectionView> connections, ConnectHint connectHint,
                              List<MetricView> metrics, LocalDate dataCompleteThrough, MismatchView mismatch,
                              boolean targetsEditable, List<MismatchView> mismatches, Boolean partial,
                              Boolean noDataYet) {
        public ChannelView(String channel, String label, String status, OffsetDateTime lastFetchedAt,
                           List<ConnectionView> connections, ConnectHint connectHint,
                           List<MetricView> metrics, LocalDate dataCompleteThrough, MismatchView mismatch,
                           boolean targetsEditable, List<MismatchView> mismatches, Boolean partial) {
            this(channel, label, status, lastFetchedAt, connections, connectHint, metrics, dataCompleteThrough,
                    mismatch, targetsEditable, mismatches, partial, null);
        }

        public ChannelView(String channel, String label, String status, OffsetDateTime lastFetchedAt,
                           List<ConnectionView> connections, ConnectHint connectHint,
                           List<MetricView> metrics, LocalDate dataCompleteThrough, MismatchView mismatch,
                           boolean targetsEditable) {
            this(channel, label, status, lastFetchedAt, connections, connectHint, metrics, dataCompleteThrough,
                    mismatch, targetsEditable, mismatch == null ? List.of() : List.of(mismatch), null);
        }

        public ChannelView(String channel, String label, String status, OffsetDateTime lastFetchedAt,
                           List<ConnectionView> connections, ConnectHint connectHint,
                           List<MetricView> metrics, LocalDate dataCompleteThrough, MismatchView mismatch) {
            this(channel, label, status, lastFetchedAt, connections, connectHint, metrics, dataCompleteThrough,
                    mismatch, true);
        }

        public ChannelView(String channel, String label, String status, OffsetDateTime lastFetchedAt,
                           List<ConnectionView> connections, ConnectHint connectHint,
                           List<MetricView> metrics, LocalDate dataCompleteThrough) {
            this(channel, label, status, lastFetchedAt, connections, connectHint, metrics, dataCompleteThrough, null, true);
        }
    }

    /** {@code basis}: COHORT when the value counts people who first opened in the window, followed to now. */
    public record FunnelStageView(String key, String label, Double value, Double previous, Double change,
                                  String source, boolean available, String unavailableReason,
                                  LocalDate dataCompleteThrough, String path, String conversionFromKey,
                                  String basis) {
        public FunnelStageView(String key, String label, Double value, Double previous, Double change,
                               String source, boolean available, String unavailableReason,
                               LocalDate dataCompleteThrough, String path, String conversionFromKey) {
            this(key, label, value, previous, change, source, available, unavailableReason, dataCompleteThrough,
                    path, conversionFromKey, "EVENT");
        }
    }

    /** {@code funnelBasis} is COHORT when any available stage is counted by cohort, else EVENT. */
    public record Overview(Window window, List<FunnelStageView> funnel, List<ChannelView> channels,
                           String funnelBasis) {
        public Overview(Window window, List<FunnelStageView> funnel, List<ChannelView> channels) {
            this(window, funnel, channels, "EVENT");
        }
    }

    public record ColumnView(String key, String label, Unit unit) {}

    /** @param state the member's current state (status, bid, ...) when its connector reports one; else null */
    public record DimensionRowView(String key, String label, List<Double> values, Map<String, Object> state) {
        public DimensionRowView(String key, String label, List<Double> values) {
            this(key, label, values, null);
        }
    }

    public record DimensionTable(String key, String label, List<ColumnView> columns, List<DimensionRowView> rows) {}

    public record ChannelDetail(Window window, ChannelView channel, List<DimensionTable> dimensions,
                                List<MarketingInsightsQueryService.Post> topPosts) {}

    private final ChannelMetricDailyRepository dailyRepository;
    private final ChannelMetricTargetRepository targetRepository;
    private final ChannelMetricDefinitionRepository definitionRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectorFeedRepository feedRepository;
    private final MarketingInsightsQueryService insightsQueryService;
    private final PublishPlatformRegistry platformRegistry;
    private final ChannelDimensionStateStore stateStore;
    private final MarketingPlatformReadRepository platformReadRepository;
    private final MetricsChangeDetector changeDetector = new MetricsChangeDetector();

    public MarketingOverviewQueryService(ChannelMetricDailyRepository dailyRepository,
                                         ChannelMetricTargetRepository targetRepository,
                                         ChannelMetricDefinitionRepository definitionRepository,
                                         ConnectionRepository connectionRepository,
                                         ConnectorFeedRepository feedRepository,
                                         MarketingInsightsQueryService insightsQueryService,
                                         PublishPlatformRegistry platformRegistry,
                                         ChannelDimensionStateStore stateStore,
                                         MarketingPlatformReadRepository platformReadRepository) {
        this.dailyRepository = dailyRepository;
        this.targetRepository = targetRepository;
        this.definitionRepository = definitionRepository;
        this.connectionRepository = connectionRepository;
        this.feedRepository = feedRepository;
        this.insightsQueryService = insightsQueryService;
        this.platformRegistry = platformRegistry;
        this.stateStore = stateStore;
        this.platformReadRepository = platformReadRepository;
    }

    // ---- reads --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Overview overview(String projectId, String windowRaw) {
        return overview(projectId, windowRaw, LocalDate.now(ZoneOffset.UTC), OffsetDateTime.now());
    }

    /**
     * {@link #overview(String, String)} with every metric of every channel, not only the ones a card shows: what
     * the alerts judge, so a target set on a metric past a card's first few is still watched.
     */
    public Overview overviewWithEveryMetric(String projectId, String windowRaw) {
        return overviewWithEveryMetric(projectId, windowRaw, LocalDate.now(ZoneOffset.UTC), OffsetDateTime.now());
    }

    /** Package-visible so a test can pin "today" and "now". */
    Overview overviewWithEveryMetric(String projectId, String windowRaw, LocalDate today, OffsetDateTime now) {
        return computeOverview(context(projectId, parseWindow(windowRaw, ROLLING_OVERVIEW_DAYS, today), today, now),
                new LinkedHashMap<>(), false);
    }

    /** Package-visible so a test can pin "today" and "now"; {@code windowRaw} is parsed like the public one. */
    Overview overview(String projectId, String windowRaw, LocalDate today, OffsetDateTime now) {
        return overview(projectId, parseWindow(windowRaw, ROLLING_OVERVIEW_DAYS, today), today, now);
    }

    /** Package-visible so a test can pin "today" and "now". */
    Overview overview(String projectId, int days, LocalDate today, OffsetDateTime now) {
        return overview(projectId, WindowSpec.rolling(days), today, now);
    }

    private Overview overview(String projectId, WindowSpec window, LocalDate today, OffsetDateTime now) {
        return computeOverview(context(projectId, window, today, now), new LinkedHashMap<>());
    }

    /**
     * Conductor's value for one metric over an explicit range of days, read from a snapshot: the sum of the settled
     * days for a flow, the latest reading at or before {@code to} for a level, the stored distinct count for a
     * window of that length ending on {@code to} for a window-unique metric. Null when it cannot be said: the
     * channel's settled data ends before {@code to}, the range starts before the loaded history, or there are no rows.
     */
    @FunctionalInterface
    public interface RangeValues {
        Double valueOver(MetricChannel channel, String metricKey, LocalDate from, LocalDate to);
    }

    /**
     * One channel with every metric (not just the headline ones) and the dimension tables the report reads.
     * {@code window} is the channel's own window (what its metric values cover; in a fixed window it ends where the
     * channel's settled data does, when that is earlier); {@code settledThrough} is the latest day its flows have
     * settled on every connection, or null when it has no connection or no settled data.
     */
    public record SnapshotChannel(ChannelView view, MetricCatalog.Channel catalog, List<DimensionTable> dimensions,
                                  Window window, LocalDate settledThrough) {
        public SnapshotChannel(ChannelView view, MetricCatalog.Channel catalog, List<DimensionTable> dimensions) {
            this(view, catalog, dimensions, null, null);
        }
    }

    /**
     * The overview plus, for each channel, every metric and (for STORE, ADS and PRODUCT) the dimension tables a
     * report needs, all from one shared context so the report adds no second pass over the data. ADS keyword rows
     * are ranked by spend and read up to {@link #REPORT_ALL_KEYWORDS} (the report lists {@link #REPORT_KEYWORDS}); PRODUCT keyword rows are read in full so they
     * can be joined to the ADS ones by keyword id.
     */
    public record Snapshot(Overview overview, Map<MetricChannel, SnapshotChannel> channels,
                           SettledDailyValues settled, RangeValues ranges) {

        public Snapshot(Overview overview, Map<MetricChannel, SnapshotChannel> channels,
                        SettledDailyValues settled) {
            this(overview, channels, settled, (channel, metric, from, to) -> null);
        }

        public Snapshot(Overview overview, Map<MetricChannel, SnapshotChannel> channels) {
            this(overview, channels, SettledDailyValues.NONE);
        }

        /** Conductor's value for one metric over an explicit range of days; see {@link RangeValues}. */
        public Double valueOver(MetricChannel channel, String metricKey, LocalDate from, LocalDate to) {
            return ranges.valueOver(channel, metricKey, from, to);
        }

        /**
         * One stored metric's settled value per day, summed across the channel's connections, over the snapshot's
         * history range (the same days the channel's metrics were read from, up to yesterday). A day with any
         * provisional row for the metric at that dimension is left out, so every value returned is one the source
         * will no longer restate. {@code dimensionKey} "" is the day total; a dimension key (for example
         * {@code source:APP_STORE_SEARCH}) reads that dimension's rows, which a channel only has loaded when its own
         * metrics read dimension rows (STORE and SOCIAL do; ADS, WEB, SEARCH and PRODUCT hold day totals only). The
         * metric need not be in the catalog. Empty when the channel is not connected or has no such rows; read from
         * rows already loaded for the snapshot, so it costs no query.
         */
        public Map<LocalDate, Double> dailyValues(MetricChannel channel, String storedMetric, String dimensionKey) {
            return settled.values(channel, storedMetric, dimensionKey);
        }
    }

    /** Settled daily sums by channel, stored metric and dimension key; built once per snapshot from the loaded rows. */
    public static final class SettledDailyValues {
        static final SettledDailyValues NONE = new SettledDailyValues(Map.of());

        private final Map<MetricChannel, Map<String, TreeMap<LocalDate, Double>>> byChannel;

        private SettledDailyValues(Map<MetricChannel, Map<String, TreeMap<LocalDate, Double>>> byChannel) {
            this.byChannel = byChannel;
        }

        private static String seriesKey(String metric, String dimensionKey) {
            return metric + '\u0000' + dimensionKey;
        }

        static SettledDailyValues of(Map<MetricChannel, List<ChannelMetricDaily>> rowsByChannel, LocalDate lastDay) {
            Map<MetricChannel, Map<String, TreeMap<LocalDate, Double>>> out = new LinkedHashMap<>();
            rowsByChannel.forEach((channel, rows) -> {
                Map<String, TreeMap<LocalDate, Double>> sums = new HashMap<>();
                Map<String, Set<LocalDate>> restating = new HashMap<>();
                for (ChannelMetricDaily row : rows) {
                    if (row.getDay().isAfter(lastDay)) {
                        continue;
                    }
                    String key = seriesKey(row.getMetric(), row.getDimensionKey());
                    if (row.isProvisional()) {
                        restating.computeIfAbsent(key, k -> new HashSet<>()).add(row.getDay());
                    } else {
                        sums.computeIfAbsent(key, k -> new TreeMap<>())
                                .merge(row.getDay(), row.getValue().doubleValue(), Double::sum);
                    }
                }
                restating.forEach((key, days) -> {
                    TreeMap<LocalDate, Double> series = sums.get(key);
                    if (series != null) {
                        series.keySet().removeAll(days);
                    }
                });
                out.put(channel, sums);
            });
            return new SettledDailyValues(out);
        }

        Map<LocalDate, Double> values(MetricChannel channel, String storedMetric, String dimensionKey) {
            TreeMap<LocalDate, Double> series = byChannel.getOrDefault(channel, Map.of())
                    .get(seriesKey(storedMetric, dimensionKey == null ? "" : dimensionKey));
            return series == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(series));
        }
    }

    /** One visible metric of one channel with its definition, as the definitions endpoint lists it. */
    public record MetricDefinitionView(String metricRef, String channel, String key, String label, Unit unit,
                                       String aggregation, MetricCatalog.Definition definition) {}

    /** Keywords the report lists (top by spend). */
    public static final int REPORT_KEYWORDS = 25;
    /** ADS keyword rows are read in full so first-time downloads can be summed; the report trims the list itself. */
    static final int REPORT_ALL_KEYWORDS = 5000;
    static final int REPORT_ADS = 100;
    static final int REPORT_PRODUCT_KEYWORDS = 5000;

    @Transactional(readOnly = true)
    public Snapshot snapshot(String projectId, String windowRaw) {
        return snapshot(projectId, windowRaw, ROLLING_OVERVIEW_DAYS);
    }

    /** As {@link #snapshot(String, String)} with the rolling window lengths the caller accepts (a fixed window is always accepted). */
    @Transactional(readOnly = true)
    public Snapshot snapshot(String projectId, String windowRaw, Set<Integer> allowedDays) {
        return snapshot(projectId, windowRaw, allowedDays, LocalDate.now(ZoneOffset.UTC), OffsetDateTime.now());
    }

    /** Public so the report's tests can pin "today" and "now". */
    public Snapshot snapshot(String projectId, int days, LocalDate today, OffsetDateTime now) {
        return snapshot(projectId, WindowSpec.rolling(days), today, now);
    }

    /** Public so tests can pin "today" and "now" with a rolling or a fixed window named as a request names it. */
    public Snapshot snapshot(String projectId, String windowRaw, LocalDate today, OffsetDateTime now) {
        return snapshot(projectId, windowRaw, ROLLING_OVERVIEW_DAYS, today, now);
    }

    /** As above, with the rolling window lengths the caller accepts (a fixed window is always accepted). */
    public Snapshot snapshot(String projectId, String windowRaw, Set<Integer> allowedDays, LocalDate today,
                             OffsetDateTime now) {
        return snapshot(projectId, parseWindow(windowRaw, allowedDays, today), today, now);
    }

    private Snapshot snapshot(String projectId, WindowSpec window, LocalDate today, OffsetDateTime now) {
        Context ctx = context(projectId, window, today, now);
        Map<MetricChannel, ChannelBuild> builds = new LinkedHashMap<>();
        Overview overview = computeOverview(ctx, builds);
        Map<MetricChannel, SnapshotChannel> channels = new LinkedHashMap<>();
        Map<MetricChannel, List<ChannelMetricDaily>> rows = new LinkedHashMap<>();
        for (ChannelBuild build : builds.values()) {
            MetricChannel id = build.channel.channel();
            List<DimensionTable> tables = List.of();
            if (build.live() && (id == MetricChannel.STORE || id == MetricChannel.ADS || id == MetricChannel.PRODUCT)) {
                tables = dimensions(ctx, build.channel, build, reportTableOptions(id));
            }
            channels.put(id, new SnapshotChannel(build.view(false), build.channel, tables, build.window,
                    build.settledThrough));
            if (build.data != null) {
                rows.put(id, build.data.rows());
            }
        }
        RangeValues ranges = (channel, metricKey, from, to) -> {
            ChannelBuild build = builds.get(channel);
            Metric metric = build == null ? null : build.channel.metric(metricKey).orElse(null);
            return metric == null ? null : valueOver(ctx, build, metric, from, to);
        };
        return new Snapshot(overview, channels, SettledDailyValues.of(rows, ctx.yesterday), ranges);
    }

    private static java.util.function.Function<Dimension, TableOptions> reportTableOptions(MetricChannel channel) {
        return dimension -> switch (channel) {
            case ADS -> switch (dimension.key()) {
                case "keywords" -> new TableOptions("spend", REPORT_ALL_KEYWORDS);
                case "ads" -> new TableOptions("spend", REPORT_ADS);
                default -> new TableOptions(null, DIMENSION_ROWS);
            };
            case PRODUCT -> new TableOptions(null, REPORT_PRODUCT_KEYWORDS);
            default -> new TableOptions(null, DIMENSION_ROWS);
        };
    }

    /** How a dimension table is ranked and capped; a null {@code sortKey} ranks by the first column. */
    private record TableOptions(String sortKey, int limit) {}

    private Overview computeOverview(Context ctx, Map<MetricChannel, ChannelBuild> builds) {
        return computeOverview(ctx, builds, true);
    }

    /** @param headlineOnly each channel lists the metrics its card shows; false lists every metric */
    private Overview computeOverview(Context ctx, Map<MetricChannel, ChannelBuild> builds, boolean headlineOnly) {
        for (MetricCatalog.Channel channel : MetricCatalog.channels()) {
            builds.put(channel.channel(), build(ctx, channel));
        }
        // One window for every funnel stage: a conversion between stages of different channels is only meaningful
        // over the same days, so it ends at the earliest data-complete day of the channels that contribute stages.
        // A channel whose data stopped long before the others' must not drag every other stage back in time.
        Set<MetricChannel> lagging = markLagging(ctx, builds);
        Window funnelWindow = funnelWindow(ctx, builds, lagging);
        List<FunnelStageView> stages = new ArrayList<>();
        for (MetricCatalog.FunnelStage stage : MetricCatalog.funnel(ctx.productChannel)) {
            stages.add(funnelStage(stage, builds.get(stage.channel()), funnelWindow,
                    lagging.contains(stage.channel()), ctx.fixed() && funnelWindow.to().isBefore(ctx.fixedTo)));
        }
        List<FunnelStageView> funnel = withConversions(stages);
        List<ChannelView> channels = builds.values().stream()
                .map(b -> b.view(headlineOnly))
                .toList();
        // The overall range spans the windows of the channels that have data, which differ by how far each is
        // data-complete. With none, there is no "data through" date to claim: both ends are null.
        List<ChannelBuild> withData = builds.values().stream().filter(ChannelBuild::hasData).toList();
        Window overall;
        if (withData.isEmpty()) {
            Window empty = windowEnding(ctx, ctx.fallbackEnd);
            overall = new Window(empty.from(), null, empty.previousFrom(), empty.previousTo(), null);
        } else {
            // The overall range is the funnel's own window (current and previous never overlap); the slowest
            // channel's data-complete day is reported separately so the page can say which data lags.
            overall = new Window(funnelWindow.from(), funnelWindow.to(), funnelWindow.previousFrom(),
                    funnelWindow.previousTo(),
                    withData.stream().map(b -> b.window.to()).min(Comparator.naturalOrder()).orElseThrow());
        }
        String funnelBasis = funnel.stream().anyMatch(s -> s.available() && "COHORT".equals(s.basis()))
                ? "COHORT" : "EVENT";
        return new Overview(overall, funnel, channels, funnelBasis);
    }

    @Transactional(readOnly = true)
    public ChannelDetail channel(String projectId, String channelRaw, String windowRaw) {
        MetricCatalog.Channel channel = requireChannel(channelRaw);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return channel(projectId, channel, parseWindow(windowRaw, ROLLING_CHANNEL_DAYS, today), today,
                OffsetDateTime.now());
    }

    ChannelDetail channel(String projectId, MetricCatalog.Channel channel, int days, LocalDate today,
                          OffsetDateTime now) {
        return channel(projectId, channel, WindowSpec.rolling(days), today, now);
    }

    /** Package-visible so a test can pin "today" and "now" with a window named as a request names it. */
    ChannelDetail channel(String projectId, MetricCatalog.Channel channel, String windowRaw, LocalDate today,
                          OffsetDateTime now) {
        return channel(projectId, channel, parseWindow(windowRaw, ROLLING_CHANNEL_DAYS, today), today, now);
    }

    private ChannelDetail channel(String projectId, MetricCatalog.Channel channel, WindowSpec window,
                                  LocalDate today, OffsetDateTime now) {
        Context ctx = context(projectId, window, today, now);
        channel = ctx.resolve(channel);
        ChannelBuild build = build(ctx, channel);
        if (build.live() && build.hasData()) {
            // same rule as the overview: compare this channel's data end with the other connected channels'
            Map<MetricChannel, ChannelBuild> builds = new LinkedHashMap<>();
            for (MetricCatalog.Channel other : MetricCatalog.channels()) {
                builds.put(other.channel(), other.channel() == channel.channel() ? build : build(ctx, other));
            }
            markLagging(ctx, builds);
        }
        List<DimensionTable> dimensions = build.live()
                ? dimensions(ctx, channel, build, d -> new TableOptions(null, DIMENSION_ROWS)) : List.of();
        // Top posts are ranked over the trailing days from now; a fixed window of past days has no such ranking.
        List<MarketingInsightsQueryService.Post> topPosts = channel.channel() == MetricChannel.SOCIAL && !ctx.fixed()
                ? insightsQueryService.topPosts(projectId, ctx.days, TOP_POSTS)
                : List.of();
        return new ChannelDetail(build.window, build.view(false), dimensions, topPosts);
    }

    /**
     * The definition of every visible metric of every channel (hidden building blocks are left out), the in-app
     * channel as this workspace's connected product backends declared it. Optional metrics are listed whether or not
     * the workspace has rows for them yet: this is what each number would mean, not what is currently shown.
     */
    @Transactional(readOnly = true)
    public List<MetricDefinitionView> definitions(String projectId) {
        List<Connection> active = connectionRepository.findByProjectId(projectId).stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .toList();
        List<MetricDefinitionView> out = new ArrayList<>();
        for (MetricCatalog.Channel catalogChannel : MetricCatalog.channels()) {
            MetricCatalog.Channel channel = catalogChannel.channel() == MetricChannel.PRODUCT
                    ? productSetup(projectId, active).channel() : catalogChannel;
            for (Metric metric : channel.visibleMetrics()) {
                out.add(new MetricDefinitionView(channel.channel().name() + "." + metric.key(),
                        channel.channel().name(), metric.key(), metric.label(), metric.unit(), metric.agg().name(),
                        metric.definition()));
            }
        }
        return out;
    }

    // ---- targets ------------------------------------------------------------------------------------

    /** Sets (replacing any existing) the target for a metric; {@code directionRaw} defaults to the metric's good direction. */
    @Transactional
    public TargetView setTarget(String projectId, String channelRaw, String metricKey, Double target,
                                String directionRaw, String updatedBy) {
        MetricCatalog.Channel channel = requireChannel(channelRaw);
        requireWorkspaceTarget(channel);
        Metric metric = requireMetric(channel, metricKey);
        if (target == null || target.isNaN() || target.isInfinite() || target < 0) {
            throw new BusinessException("Target must be a number of zero or more");
        }
        String direction = directionRaw == null
                ? metric.good().name()
                : parseDirection(directionRaw);
        ChannelMetricTarget row = targetRepository
                .findByProjectIdAndChannelAndMetric(projectId, channel.channel().name(), metric.key())
                .orElseGet(() -> {
                    ChannelMetricTarget created = new ChannelMetricTarget();
                    created.setProjectId(projectId);
                    created.setChannel(channel.channel().name());
                    created.setMetric(metric.key());
                    return created;
                });
        row.setTarget(BigDecimal.valueOf(target));
        row.setDirection(direction);
        row.setUpdatedBy(updatedBy);
        row.setUpdatedAt(OffsetDateTime.now());
        targetRepository.save(row);
        return new TargetView(target, direction);
    }

    @Transactional
    public void clearTarget(String projectId, String channelRaw, String metricKey) {
        MetricCatalog.Channel channel = requireChannel(channelRaw);
        requireWorkspaceTarget(channel);
        Metric metric = requireMetric(channel, metricKey);
        targetRepository.findByProjectIdAndChannelAndMetric(projectId, channel.channel().name(), metric.key())
                .ifPresent(targetRepository::delete);
    }

    /** In-app targets come from the product metrics feed, so they cannot be set or cleared in Conductor. */
    private static void requireWorkspaceTarget(MetricCatalog.Channel channel) {
        if (channel.channel() == MetricChannel.PRODUCT) {
            throw new ConflictException("In-app targets are set in your product metrics connection, not here."
                    + " Change the target there and it will update on the next sync.");
        }
    }

    private static String parseDirection(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("UP") && !normalized.equals("DOWN")) {
            throw new BusinessException("Unknown direction '" + raw + "'; expected UP or DOWN");
        }
        return normalized;
    }

    private static MetricCatalog.Channel requireChannel(String raw) {
        return MetricCatalog.channel(raw)
                .orElseThrow(() -> new BusinessException("Unknown channel '" + raw + "'; expected one of "
                        + MetricCatalog.channels().stream().map(c -> c.channel().name()).toList()));
    }

    private static Metric requireMetric(MetricCatalog.Channel channel, String key) {
        return channel.visibleMetrics().stream().filter(m -> m.key().equals(key)).findFirst()
                .orElseThrow(() -> new BusinessException("Unknown metric '" + key + "' for channel "
                        + channel.channel().name()));
    }

    /**
     * A requested window: a rolling one of {@code days} days ending where each channel's data does, or (when
     * {@code from} and {@code to} are set) exactly those days for every channel, {@code days} being their count.
     */
    record WindowSpec(int days, LocalDate from, LocalDate to) {
        static WindowSpec rolling(int days) {
            return new WindowSpec(days, null, null);
        }

        static WindowSpec fixed(LocalDate from, LocalDate to) {
            return new WindowSpec((int) (to.toEpochDay() - from.toEpochDay() + 1), from, to);
        }

        boolean fixed() {
            return from != null;
        }
    }

    /**
     * Reads a window parameter: {@code 7d}, {@code 28d} ... for the lengths {@code allowed}, or a fixed range
     * {@code YYYY-MM-DD..YYYY-MM-DD}. A fixed range must run first day to last day, end no later than yesterday
     * (UTC: today is still partial) and span at most {@link #FIXED_WINDOW_MAX_DAYS} days; anything else is a 400
     * with a plain message. A blank window is the default, {@value #DEFAULT_WINDOW}.
     */
    static WindowSpec parseWindow(String raw, Set<Integer> allowed, LocalDate today) {
        String normalized = raw == null || raw.isBlank() ? DEFAULT_WINDOW : raw.trim().toLowerCase(Locale.ROOT);
        Matcher fixed = FIXED_WINDOW.matcher(normalized);
        if (fixed.matches()) {
            return fixedWindow(raw, fixed.group(1), fixed.group(2), today);
        }
        if (normalized.endsWith("d")) {
            try {
                int days = Integer.parseInt(normalized.substring(0, normalized.length() - 1));
                if (allowed.contains(days)) {
                    return WindowSpec.rolling(days);
                }
            } catch (NumberFormatException ignored) {
                // falls through to the 400 below
            }
        }
        throw new BusinessException("Unknown window '" + raw + "'; expected "
                + allowed.stream().sorted().map(d -> d + "d").toList() + " or a range of days YYYY-MM-DD..YYYY-MM-DD");
    }

    private static WindowSpec fixedWindow(String raw, String fromText, String toText, LocalDate today) {
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(fromText);
            to = LocalDate.parse(toText);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException("Unknown window '" + raw + "': not a calendar day in YYYY-MM-DD..YYYY-MM-DD");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("Window starts on " + from + ", after it ends on " + to
                    + "; give the first day, then the last");
        }
        LocalDate yesterday = today.minusDays(1);
        if (to.isAfter(yesterday)) {
            throw new BusinessException("Window ends on " + to + ", but only days up to " + yesterday
                    + " are complete; today is still partial");
        }
        WindowSpec spec = WindowSpec.fixed(from, to);
        if (spec.days() > FIXED_WINDOW_MAX_DAYS) {
            throw new BusinessException("Window of " + spec.days() + " days is too long; ask for at most "
                    + FIXED_WINDOW_MAX_DAYS + " days");
        }
        return spec;
    }

    // ---- context ------------------------------------------------------------------------------------

    /** The workspace's product (in-app) connections, the metric definitions they declared and the channel they make. */
    private record ProductSetup(Map<String, Connection> connections, List<ChannelMetricDefinitionRow> definitions,
                                MetricCatalog.Channel channel) {}

    /**
     * Resolves the in-app channel for a workspace from its active connections: the product-metrics connections in
     * order, the definitions they declared (first connection's wins per key) and each connection's label for the
     * definition lines.
     */
    private ProductSetup productSetup(String projectId, List<Connection> activeConnections) {
        Map<String, Connection> productConnections = new LinkedHashMap<>();
        for (Connection c : activeConnections) {
            if (MetricCatalog.PRODUCT_CONNECTOR_ID.equals(c.getConnectorId())) {
                productConnections.put(c.getId(), c);
            }
        }
        List<String> connectionOrder = new ArrayList<>(productConnections.keySet());
        List<ChannelMetricDefinitionRow> definitions = productConnections.isEmpty() ? List.of()
                : definitionRepository.findByProjectId(projectId).stream()
                        .filter(d -> productConnections.containsKey(d.getConnectionId()))
                        .sorted(Comparator.comparing((ChannelMetricDefinitionRow d) ->
                                connectionOrder.indexOf(d.getConnectionId())))
                        .toList();
        Map<String, String> labels = new HashMap<>();
        productConnections.forEach((id, c) -> {
            if (c.getDisplayLabel() != null && !c.getDisplayLabel().isBlank()) {
                labels.put(id, c.getDisplayLabel());
            }
        });
        return new ProductSetup(productConnections, definitions, MetricCatalog.productChannel(definitions, labels));
    }

    /** Everything one request shares across channels. */
    private final class Context {
        final String projectId;
        /** The window's length in days (a fixed window's own count). */
        final int days;
        /** The first and last day of a fixed window; both null for a rolling one. */
        final LocalDate fixedFrom;
        final LocalDate fixedTo;
        final LocalDate yesterday;
        final LocalDate today;
        /** Where a window ends when a channel has no settled data. */
        final LocalDate fallbackEnd;
        final LocalDate historyStart;
        final OffsetDateTime now;
        final List<Connection> connections;
        final List<ConnectorFeed> feeds;
        final Map<String, ChannelMetricTarget> targets;
        /** The in-app channel as the connected backends declared it. */
        final MetricCatalog.Channel productChannel;
        /** Targets declared by the product feeds, by metric key (read-only). */
        final Map<String, TargetView> feedTargets = new HashMap<>();
        final Set<String> productConnectionIds = new HashSet<>();
        private List<ChannelMetricDaily> referenceRows;
        private List<MarketingPlatformRead> platformReads;

        Context(String projectId, WindowSpec window, LocalDate today, OffsetDateTime now) {
            this.projectId = projectId;
            int days = window.days();
            this.days = days;
            this.fixedFrom = window.from();
            this.fixedTo = window.to();
            this.now = now;
            LocalDate to = today.minusDays(1);
            this.yesterday = to;
            this.today = today;
            // Not yesterday: the UTC day before today is already the user's local today in the Americas, and a
            // window must never end on a day that is still going. Two days back is complete everywhere.
            this.fallbackEnd = today.minusDays(2);
            int lookback = Math.max(2 * days, Math.min(days * (HISTORY_PERIODS + 1), MAX_HISTORY_DAYS));
            // The history reaches back from the window's last day, so a fixed window in the past still has its
            // previous window and noise baseline loaded.
            this.historyStart = (window.fixed() ? window.to() : to).minusDays(lookback - 1L);
            this.connections = connectionRepository.findByProjectId(projectId).stream()
                    .filter(c -> "ACTIVE".equals(c.getStatus()))
                    .toList();
            this.feeds = feedRepository.findByProjectId(projectId).stream()
                    .filter(f -> MetricCatalog.FEED_INGEST_ID.equals(f.getIngestId()))
                    .toList();
            this.targets = new HashMap<>();
            for (ChannelMetricTarget t : targetRepository.findByProjectId(projectId)) {
                targets.put(t.getChannel() + "/" + t.getMetric(), t);
            }
            ProductSetup product = productSetup(projectId, connections);
            Map<String, Connection> productConnections = product.connections();
            productConnectionIds.addAll(productConnections.keySet());
            this.productChannel = product.channel();
            for (ChannelMetricDefinitionRow d : product.definitions()) {
                if (d.getTargetValue() != null && !feedTargets.containsKey(d.getMetric())) {
                    Connection c = productConnections.get(d.getConnectionId());
                    String label = c.getDisplayLabel() != null ? c.getDisplayLabel()
                            : MetricCatalog.connectorName(c.getConnectorId());
                    feedTargets.put(d.getMetric(), new TargetView(d.getTargetValue().doubleValue(),
                            d.getTargetDirection() != null ? d.getTargetDirection() : d.getGood(), true,
                            "Set in " + label, d.getTargetWindow(),
                            d.getWatchBandPct() == null ? null : d.getWatchBandPct().doubleValue()));
                }
            }
        }

        boolean fixed() {
            return fixedFrom != null;
        }

        MetricCatalog.Channel resolve(MetricCatalog.Channel channel) {
            return channel.channel() == MetricChannel.PRODUCT ? productChannel : channel;
        }

        /** The product backends' reference figures (settled days only), loaded once per request. */
        List<ChannelMetricDaily> referenceRows() {
            if (referenceRows == null) {
                referenceRows = productConnectionIds.isEmpty() ? List.of()
                        : dailyRepository.findByProjectIdAndChannelAndMetricInAndDayBetween(projectId, "PRODUCT",
                                ReferencePairs.referenceMetrics(), historyStart, yesterday).stream()
                        .filter(r -> r.getDimensionKey().isEmpty() && !r.isProvisional()
                                && productConnectionIds.contains(r.getConnectionId()))
                        .toList();
            }
            return referenceRows;
        }

        /** The newest direct platform read of each (channel, metric), loaded once per request. */
        List<MarketingPlatformRead> platformReads() {
            if (platformReads == null) {
                platformReads = platformReadRepository.findLatestByProject(projectId);
            }
            return platformReads;
        }
    }

    private Context context(String projectId, WindowSpec window, LocalDate today, OffsetDateTime now) {
        return new Context(projectId, window, today, now);
    }

    // ---- channel build ------------------------------------------------------------------------------

    private final class ChannelBuild {
        final MetricCatalog.Channel channel;
        String status;
        final OffsetDateTime lastFetchedAt;
        final List<ConnectionView> connections;
        final ConnectHint connectHint;
        final List<MetricView> metrics;
        final Window window;
        /** Every second source that disagrees, largest gap first. */
        final List<MismatchView> mismatches;
        /** True only in a fixed window the channel's settled data does not reach the end of. */
        final Boolean partial;
        /** The latest day the channel's flows are settled on every connection; null with no connection or no settled data. */
        final LocalDate settledThrough;
        /** The channel's rows (null when it has no connection), so the funnel can re-read them over its own window. */
        ChannelData data;
        /** True when the channel is connected but has no complete day of data, ever. */
        boolean noDataYet;

        ChannelBuild(MetricCatalog.Channel channel, String status, OffsetDateTime lastFetchedAt,
                     List<ConnectionView> connections, ConnectHint connectHint, List<MetricView> metrics,
                     Window window) {
            this(channel, status, lastFetchedAt, connections, connectHint, metrics, window, List.of(), null, null);
        }

        ChannelBuild(MetricCatalog.Channel channel, String status, OffsetDateTime lastFetchedAt,
                     List<ConnectionView> connections, ConnectHint connectHint, List<MetricView> metrics,
                     Window window, List<MismatchView> mismatches, Boolean partial, LocalDate settledThrough) {
            this.mismatches = mismatches;
            this.partial = partial;
            this.settledThrough = settledThrough;
            this.window = window;
            this.channel = channel;
            this.status = status;
            this.lastFetchedAt = lastFetchedAt;
            this.connections = connections;
            this.connectHint = connectHint;
            this.metrics = metrics;
        }

        boolean hasData() {
            return metrics.stream().anyMatch(m -> m.value() != null || m.previous() != null);
        }

        boolean live() {
            return status.equals("LIVE") || status.equals("STALE") || status.equals("MISMATCH");
        }

        Optional<MetricView> metric(String key) {
            return metrics.stream().filter(m -> m.metric().equals(key)).findFirst();
        }

        /**
         * The metrics an overview card gets. Other channels show their first few. The in-app channel's first
         * metrics are the early funnel stages, which the funnel strip already shows, so it leads with the last
         * two stages (trials, paid), then the ratios (cost per trial), then the rest in order. It keeps every
         * metric, because the funnel strip reads the stage flags and targets from them; a card shows the first
         * {@code headline}.
         */
        private List<MetricView> headlineMetrics() {
            if (channel.channel() != MetricChannel.PRODUCT) {
                return metrics.stream().limit(channel.headline()).toList();
            }
            // A feed that ranks its headline metrics decides what the card leads with.
            if (metrics.stream().anyMatch(m -> m.headline() != null)) {
                List<MetricView> ranked = new java.util.ArrayList<>(metrics.stream()
                        .filter(m -> m.headline() != null)
                        .sorted(Comparator.comparing(MetricView::headline)).toList());
                metrics.stream().filter(m -> m.headline() == null).forEach(ranked::add);
                return ranked;
            }
            List<MetricView> stages = metrics.stream()
                    .filter(m -> channel.metric(m.metric()).map(c -> c.funnelOrder() != null).orElse(false))
                    .toList();
            List<MetricView> ordered = new java.util.ArrayList<>(stages.subList(Math.max(0, stages.size() - 2), stages.size()));
            metrics.stream()
                    .filter(m -> channel.metric(m.metric()).map(c -> c.agg() == MetricCatalog.Aggregation.DERIVED).orElse(false))
                    .filter(m -> !ordered.contains(m))
                    .forEach(ordered::add);
            metrics.stream().filter(m -> !ordered.contains(m)).forEach(ordered::add);
            return ordered;
        }

        /** The mismatch old clients read: the backend one with the largest gap, else the platform read's. */
        private MismatchView legacyMismatch() {
            return mismatches.stream().filter(m -> KIND_BACKEND.equals(m.kind())).findFirst()
                    .or(() -> mismatches.stream().findFirst()).orElse(null);
        }

        ChannelView view(boolean headlineOnly) {
            List<MetricView> shown = headlineOnly ? headlineMetrics() : metrics;
            return new ChannelView(channel.channel().name(), channel.label(), status, lastFetchedAt, connections,
                    connectHint, shown, window.to(), legacyMismatch(), channel.channel() != MetricChannel.PRODUCT,
                    mismatches, partial, noDataYet ? Boolean.TRUE : null);
        }
    }

    private ChannelBuild build(Context ctx, MetricCatalog.Channel catalogChannel) {
        MetricCatalog.Channel channel = ctx.resolve(catalogChannel);
        List<Connection> connections = ctx.connections.stream()
                .filter(c -> channel.connectorIds().contains(c.getConnectorId()))
                .toList();
        if (channel.connectorIds().isEmpty()) {
            return new ChannelBuild(channel, "COMING_SOON", null, List.of(), null, List.of(),
                    windowEnding(ctx, ctx.fallbackEnd));
        }
        Set<String> connectedIds = new HashSet<>();
        connections.forEach(c -> connectedIds.add(c.getConnectorId()));
        String firstMissing = channel.connectorIds().stream().filter(id -> !connectedIds.contains(id)).findFirst()
                .orElse(channel.connectorIds().get(0));
        ConnectHint hint = new ConnectHint(firstMissing, channel.connectorIds().stream()
                .map(id -> new ConnectorRef(id, MetricCatalog.connectorName(id))).toList());
        if (connections.isEmpty()) {
            return new ChannelBuild(channel, "NOT_CONNECTED", null, List.of(), hint, List.of(),
                    windowEnding(ctx, ctx.fallbackEnd));
        }

        List<ConnectionView> connectionViews = new ArrayList<>();
        OffsetDateTime lastFetchedAt = null;
        boolean anyLive = false;
        for (Connection connection : connections) {
            Optional<ConnectorFeed> feed = ctx.feeds.stream()
                    .filter(f -> f.getConnectionId().equals(connection.getId())).findFirst();
            boolean live = isLive(connection, feed.orElse(null), ctx.now);
            anyLive |= live;
            if (feed.isPresent() && feed.get().getLastSuccessAt() != null
                    && (lastFetchedAt == null || feed.get().getLastSuccessAt().isAfter(lastFetchedAt))) {
                lastFetchedAt = feed.get().getLastSuccessAt();
            }
            connectionViews.add(new ConnectionView(connection.getId(), connection.getConnectorId(),
                    connection.getDisplayLabel() != null ? connection.getDisplayLabel() : connection.getConnectorId(),
                    live ? "LIVE" : "STALE"));
        }

        Set<String> connectionIds = new HashSet<>();
        connections.forEach(c -> connectionIds.add(c.getId()));
        List<ChannelMetricDaily> rows = load(ctx, channel, connectionIds);
        LocalDate settled = dataCompleteThrough(ctx, channel, rows);
        // Nothing settled inside the lookback: report the real last settled day (older than the lookback) and
        // call the channel STALE, rather than inventing an end date that hides the gap.
        LocalDate olderSettled = settled == null ? lastSettledBeforeLookback(ctx, channel, connectionIds) : null;
        LocalDate completeThrough = settled != null ? settled
                : olderSettled != null ? olderSettled : ctx.fallbackEnd;
        Window window = windowEnding(ctx, completeThrough);
        LocalDate settledThrough = settled != null ? settled : olderSettled;
        // Only a fixed window can outrun the data: a rolling one ends where the channel's data does.
        boolean partial = ctx.fixed() && settledThrough != null && settledThrough.isBefore(ctx.fixedTo);
        ChannelData data = new ChannelData(channel, rows);
        Map<String, LocalDate> reportedBefore = snapshotsBeforeLookback(ctx, channel, connectionIds, data);
        // an optional snapshot with a target that was reported before the lookback stays, so its stop is seen
        List<Metric> visible = channel.visibleMetrics().stream()
                .filter(m -> !m.optional() || data.hasRows(m) || reportedBefore.containsKey(m.key())).toList();
        List<MetricView> metrics = new ArrayList<>();
        for (Metric metric : visible) {
            metrics.add(metricView(ctx, channel, data, metric, window, partial, reportedBefore));
        }
        List<MismatchView> mismatches = mismatches(ctx, channel, data, window, settledThrough);
        String status = olderSettled != null ? "STALE"
                : !mismatches.isEmpty() ? "MISMATCH" : anyLive ? "LIVE" : "STALE";
        ChannelBuild built = new ChannelBuild(channel, status, lastFetchedAt, connectionViews, hint, metrics, window,
                mismatches, partial ? Boolean.TRUE : null, settledThrough);
        built.data = data;
        built.noDataYet = settledThrough == null;
        return built;
    }

    /** The target on {@code metric}: the product feed's own, else one a person set in Conductor; null with none. */
    private static TargetView targetOf(Context ctx, MetricCatalog.Channel channel, Metric metric) {
        TargetView feedTarget = channel.channel() == MetricChannel.PRODUCT ? ctx.feedTargets.get(metric.key()) : null;
        if (feedTarget != null) {
            return feedTarget;
        }
        ChannelMetricTarget target = ctx.targets.get(channel.channel().name() + "/" + metric.key());
        return target == null ? null : new TargetView(target.getTarget().doubleValue(), target.getDirection());
    }

    /**
     * For the targeted snapshots (levels) with no row inside the lookback, the last day each was reported before it,
     * by metric key, so a snapshot the feed stopped sending long ago still says when it was last read (see
     * {@link #stoppedArriving}) instead of reading as never reported. A row counts for a level exactly as
     * {@link ChannelData} reads it. One query, and none in the usual case of every targeted level having rows.
     *
     * <p>Only snapshots: a feed sends a reading for one every day, so its absence means it stopped. A flow with no
     * rows may simply have had no events (not every feed writes zero days), so it is never called stopped.
     */
    private Map<String, LocalDate> snapshotsBeforeLookback(Context ctx, MetricCatalog.Channel channel,
                                                           Set<String> connectionIds, ChannelData data) {
        LocalDate end = ctx.fixed() ? ctx.fixedTo : ctx.today;
        List<Metric> missing = channel.visibleMetrics().stream()
                .filter(m -> m.agg() == Aggregation.LEVEL && data.latestDay(m, end) == null
                        && targetOf(ctx, channel, m) != null)
                .toList();
        if (missing.isEmpty()) {
            return Map.of();
        }
        List<Object[]> latest = dailyRepository.latestDayByMetric(ctx.projectId, channel.channel().name(),
                connectionIds, missing.stream().map(Metric::stored).collect(java.util.stream.Collectors.toSet()),
                ctx.historyStart.minusDays(1));
        Map<String, LocalDate> out = new HashMap<>();
        for (Metric metric : missing) {
            for (Object[] row : latest) {
                if (metric.stored().equals(row[0]) && readsStoredRow(channel, metric, (String) row[1])) {
                    out.merge(metric.key(), (LocalDate) row[2], (a, b) -> b.isAfter(a) ? b : a);
                }
            }
        }
        return out;
    }

    /**
     * Whether the overview would read a stored row of {@code dimensionKey} as {@code metric}'s: it loads per-dimension
     * rows only for a channel that reads them, then applies {@link ChannelData#reads}.
     */
    static boolean readsStoredRow(MetricCatalog.Channel channel, Metric metric, String dimensionKey) {
        return (dimensionKey.isEmpty() || channel.usesDimensionSeries()) && ChannelData.reads(metric, dimensionKey);
    }

    /** The slowest connection's latest settled day before the lookback began, or null if none has one. */
    private LocalDate lastSettledBeforeLookback(Context ctx, MetricCatalog.Channel channel, Set<String> connectionIds) {
        List<Object[]> latest = dailyRepository.latestSettledDayByConnection(ctx.projectId,
                channel.channel().name(), connectionIds, ctx.historyStart.minusDays(1));
        return latest.stream().map(r -> (LocalDate) r[1]).min(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * Every second source that disagrees with this channel's own figures, largest gap first:
     * <ul>
     *   <li>the product backend's reference for each pair of {@link ReferencePairs}, over the settled days both
     *       sides have inside the window (with no day in common there is nothing to say);</li>
     *   <li>the newest direct platform read of each metric, against the channel's own figure over exactly the
     *       read's days (nothing to say while the channel has not settled those days).</li>
     * </ul>
     * Both are judged by {@link ReconciliationBand}.
     */
    private List<MismatchView> mismatches(Context ctx, MetricCatalog.Channel channel, ChannelData data, Window w,
                                          LocalDate settledThrough) {
        List<MismatchView> out = new ArrayList<>();
        String channelSource = sourceNames(ctx, channel.connectorIds(), channel.label());
        for (ReferencePairs.Pair pair : ReferencePairs.forChannel(channel.channel())) {
            Metric own = channel.metric(pair.metric()).orElse(null);
            if (own == null) {
                continue;
            }
            Map<LocalDate, Double> ownByDay = new TreeMap<>();
            for (LocalDate day = w.from(); !day.isAfter(w.to()); day = day.plusDays(1)) {
                Double value = data.dayValue(own, day);
                if (value != null && !data.isProvisional(own, day)) {
                    ownByDay.put(day, value);
                }
            }
            Map<LocalDate, Double> referenceByDay = new TreeMap<>();
            for (ChannelMetricDaily row : ctx.referenceRows()) {
                if (row.getMetric().equals(pair.reference())) {
                    referenceByDay.merge(row.getDay(), row.getValue().doubleValue(), Double::sum);
                }
            }
            ReferencePairs.compare(ownByDay, referenceByDay, w.from(), w.to()).ifPresent(c -> {
                if (!ReconciliationBand.matches(c.ownSum(), c.referenceSum(), judgedByPercentOnly(own))) {
                    out.add(new MismatchView(own.key(), own.label(), c.referenceSum(), c.ownSum(),
                            ReconciliationBand.gap(c.ownSum(), c.referenceSum()), c.first(), c.last(), channelSource,
                            sourceNames(ctx, List.of(MetricCatalog.PRODUCT_CONNECTOR_ID), "Product metrics"),
                            KIND_BACKEND));
                }
            });
        }
        for (MarketingPlatformRead read : ctx.platformReads()) {
            Metric metric = read.getChannel().equals(channel.channel().name())
                    ? channel.metric(read.getMetric()).filter(m -> !m.hidden()).orElse(null) : null;
            if (metric == null || metric.agg() == Aggregation.DERIVED) {
                continue;
            }
            Double conductor = valueOver(ctx, data, settledThrough, metric, read.getDayFrom(), read.getDayTo());
            double platform = read.getValue().doubleValue();
            if (conductor != null && !ReconciliationBand.matches(conductor, platform, judgedByPercentOnly(metric))) {
                out.add(new MismatchView(metric.key(), metric.label(), platform, conductor,
                        ReconciliationBand.gap(conductor, platform), read.getDayFrom(), read.getDayTo(), channelSource,
                        read.getSourceName(), KIND_PLATFORM));
            }
        }
        out.sort(Comparator.comparingDouble(MismatchView::gapPct).reversed()
                .thenComparing(MismatchView::metric).thenComparing(MismatchView::kind));
        return out;
    }

    /**
     * True for a figure the reconciliation band judges by percent only: money and ratings, which have no
     * whole-number "one unit" to allow. A count also gets the one-unit allowance below 50.
     */
    public static boolean judgedByPercentOnly(Metric metric) {
        return metric.unit() != Unit.COUNT;
    }

    /** {@link #valueOver(Context, ChannelData, LocalDate, Metric, LocalDate, LocalDate)} for a built channel. */
    private Double valueOver(Context ctx, ChannelBuild build, Metric metric, LocalDate from, LocalDate to) {
        return valueOver(ctx, build.data, build.settledThrough, metric, from, to);
    }

    /**
     * The channel's own figure for {@code metric} over {@code from}..{@code to}: its settled days (a flow), its
     * latest reading at or before {@code to} (a level) or its stored distinct count for a window of that length
     * ending on {@code to}. Null when that cannot be said without guessing: no data, the channel's settled data ends
     * before {@code to}, or the days are older than the history this request loaded.
     */
    private static Double valueOver(Context ctx, ChannelData data, LocalDate settledThrough, Metric metric,
                                    LocalDate from, LocalDate to) {
        if (data == null || settledThrough == null || to.isBefore(from) || settledThrough.isBefore(to)) {
            return null;
        }
        boolean needsEveryDay = metric.agg() == Aggregation.SUM || metric.agg() == Aggregation.DERIVED;
        if ((needsEveryDay ? from : to).isBefore(ctx.historyStart)) {
            return null;
        }
        return data.valueOver(metric, from, to);
    }

    /** The display labels of the project's connections for these connectors, e.g. "Rexipe" or "Rexipe, Beta". */
    private static String sourceNames(Context ctx, List<String> connectorIds, String fallback) {
        String names = ctx.connections.stream()
                .filter(c -> connectorIds.contains(c.getConnectorId()))
                .map(c -> c.getDisplayLabel() != null && !c.getDisplayLabel().isBlank() ? c.getDisplayLabel()
                        : MetricCatalog.connectorName(c.getConnectorId()))
                .distinct().collect(java.util.stream.Collectors.joining(", "));
        return names.isEmpty() ? fallback : names;
    }

    /** Live: a recent successful run, an active feed and a healthy connection. */
    private static boolean isLive(Connection connection, ConnectorFeed feed, OffsetDateTime now) {
        if (feed == null || !feed.isEnabled() || feed.getStatus() != ConnectorFeedStatus.ACTIVE) {
            return false;
        }
        String health = connection.getHealthStatus();
        if ("DEGRADED".equals(health) || "SETUP_REQUIRED".equals(health)) {
            return false;
        }
        OffsetDateTime last = feed.getLastSuccessAt();
        return last != null && last.plusMinutes((long) STALE_INTERVALS * feed.getIntervalMinutes()).isAfter(now);
    }

    /**
     * The window ending on {@code to}; in a fixed window, the fixed days instead, ending on {@code to} only when
     * that is earlier than the window's last day (a channel whose data stops short of it).
     */
    private static Window windowEnding(Context ctx, LocalDate to) {
        if (ctx.fixed()) {
            LocalDate end = to.isAfter(ctx.fixedTo) ? ctx.fixedTo : to;
            return new Window(ctx.fixedFrom, end, ctx.fixedFrom.minusDays(ctx.days), ctx.fixedFrom.minusDays(1), end);
        }
        LocalDate from = to.minusDays(ctx.days - 1L);
        return new Window(from, to, from.minusDays(ctx.days), from.minusDays(1), to);
    }

    /**
     * The latest day with non-provisional data on every connection that has any: a source that restates
     * (Search Console lags about three days) must not have its short window compared with a full previous
     * one. The slowest connection wins. Only flows count: a snapshot (a level such as followers) is a
     * point-in-time reading with nothing to restate, so it never holds the date back. A channel with only
     * snapshots is as current as its latest reading, no later than yesterday (the complete days); one with no settled data at all returns null (the caller picks the end).
     */
    private static @org.springframework.lang.Nullable LocalDate dataCompleteThrough(Context ctx, MetricCatalog.Channel channel,
                                                 List<ChannelMetricDaily> rows) {
        Map<String, LocalDate> latestByConnection = new HashMap<>();
        LocalDate latestSnapshot = null;
        for (ChannelMetricDaily row : rows) {
            Metric metric = channel.metric(row.getMetric()).orElse(null);
            if (metric == null) {
                continue;
            }
            if (metric.agg() == Aggregation.LEVEL) {
                LocalDate day = row.getDay().isAfter(ctx.yesterday) ? ctx.yesterday : row.getDay();
                if (latestSnapshot == null || day.isAfter(latestSnapshot)) {
                    latestSnapshot = day;
                }
            } else if (!row.isProvisional() && !row.getDay().isAfter(ctx.yesterday)) {
                latestByConnection.merge(row.getConnectionId(), row.getDay(), (a, b) -> b.isAfter(a) ? b : a);
            }
        }
        return latestByConnection.values().stream().min(Comparator.naturalOrder())
                .orElse(latestSnapshot);
    }

    private List<ChannelMetricDaily> load(Context ctx, MetricCatalog.Channel channel, Set<String> connectionIds) {
        String name = channel.channel().name();
        List<ChannelMetricDaily> rows = channel.usesDimensionSeries()
                ? dailyRepository.findByProjectIdAndChannelAndDayBetweenOrderByDayAsc(
                        ctx.projectId, name, ctx.historyStart, ctx.today)
                : dailyRepository.findByProjectIdAndChannelAndDimensionKeyAndDayBetween(
                        ctx.projectId, name, "", ctx.historyStart, ctx.yesterday);
        return rows.stream().filter(r -> connectionIds.contains(r.getConnectionId())).toList();
    }

    // ---- metric math --------------------------------------------------------------------------------

    /** A channel's rows indexed by metric, with the roll-up rules of {@link MetricCatalog.Aggregation}. */
    private static final class ChannelData {
        private final MetricCatalog.Channel channel;
        /** SUM: total across connections and dimensions per day. */
        private final Map<String, TreeMap<LocalDate, Double>> daily = new HashMap<>();
        /** LEVEL: one series per (connection, dimension), summed after carrying each forward. */
        private final Map<String, Map<String, TreeMap<LocalDate, Double>>> levels = new HashMap<>();
        private final Map<String, Set<LocalDate>> provisional = new HashMap<>();
        /** Observations behind each day's value, when the feed said (summed across connections). */
        private final Map<String, TreeMap<LocalDate, Long>> samples = new HashMap<>();
        /** WINDOW_UNIQUE: metric key, then window length in days, then the day the window ends: the stored count. */
        private final Map<String, Map<Integer, TreeMap<LocalDate, Double>>> windowUniques = new HashMap<>();
        private final List<ChannelMetricDaily> rows;

        ChannelData(MetricCatalog.Channel channel, List<ChannelMetricDaily> rows) {
            this.channel = channel;
            this.rows = rows;
            // a row feeds every metric that reads its stored name, not only the one with that key
            Map<String, List<Metric>> readers = new HashMap<>();
            for (Metric metric : channel.metrics()) {
                readers.computeIfAbsent(metric.stored(), k -> new ArrayList<>()).add(metric);
            }
            List<Metric> windowUnique = channel.metrics().stream()
                    .filter(m -> m.agg() == Aggregation.WINDOW_UNIQUE).toList();
            for (ChannelMetricDaily row : rows) {
                for (Metric metric : readers.getOrDefault(row.getMetric(), List.of())) {
                    if (reads(metric, row.getDimensionKey())) {
                        add(metric, row);
                    }
                }
                if (row.getDimensionKey().isEmpty()) {
                    for (Metric metric : windowUnique) {
                        addWindowUnique(metric, row);
                    }
                }
            }
        }

        List<ChannelMetricDaily> rows() {
            return rows;
        }

        /** Whether a row of {@code dimensionKey} belongs to {@code metric}: its one dimension key, or else the '' total (a level or a per-dimension metric reads them all). */
        static boolean reads(Metric metric, String dimensionKey) {
            if (metric.dimensionKey() != null) {
                return metric.dimensionKey().equals(dimensionKey);
            }
            // a total metric: its per-dimension rows (loaded for the channel's level series) would double count
            return metric.agg() == Aggregation.LEVEL || metric.fromDimensions() || dimensionKey.isEmpty();
        }

        private void add(Metric metric, ChannelMetricDaily row) {
            if (metric.agg() == Aggregation.LEVEL) {
                levels.computeIfAbsent(metric.key(), k -> new HashMap<>())
                        .computeIfAbsent(row.getConnectionId() + "|" + row.getDimensionKey(), k -> new TreeMap<>())
                        .put(row.getDay(), row.getValue().doubleValue());
            } else {
                daily.computeIfAbsent(metric.key(), k -> new TreeMap<>())
                        .merge(row.getDay(), row.getValue().doubleValue(), Double::sum);
            }
            if (row.isProvisional()) {
                provisional.computeIfAbsent(metric.key(), k -> new HashSet<>()).add(row.getDay());
            }
            if (row.getSampleSize() != null) {
                samples.computeIfAbsent(metric.key(), k -> new TreeMap<>())
                        .merge(row.getDay(), row.getSampleSize(), Long::sum);
            }
        }

        /** Files a stored {@code <key>_unique_<N>d} row under its window length; any other row is ignored. */
        private void addWindowUnique(Metric metric, ChannelMetricDaily row) {
            String prefix = metric.key() + "_unique_";
            String name = row.getMetric();
            if (!name.startsWith(prefix) || !name.endsWith("d")) {
                return;
            }
            try {
                int days = Integer.parseInt(name.substring(prefix.length(), name.length() - 1));
                windowUniques.computeIfAbsent(metric.key(), k -> new HashMap<>())
                        .computeIfAbsent(days, k -> new TreeMap<>())
                        .merge(row.getDay(), row.getValue().doubleValue(), Double::sum);
            } catch (NumberFormatException ignored) {
                // not a window length: not one of this metric's stored series
            }
        }

        /** The stored distinct count for a window of {@code from}..{@code to}, read on its last day; null when there is none. */
        private Double windowUniqueValue(Metric metric, LocalDate from, LocalDate to) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
            TreeMap<LocalDate, Double> series = windowUniques.getOrDefault(metric.key(), Map.of())
                    .get((int) days);
            return series == null ? null : series.get(to);
        }

        /** The first day a stored distinct count exists for a window of {@code days} days, or null. */
        LocalDate firstWindowUniqueDay(Metric metric, int days) {
            TreeMap<LocalDate, Double> series = windowUniques.getOrDefault(metric.key(), Map.of()).get(days);
            return series == null || series.isEmpty() ? null : series.firstKey();
        }

        /**
         * The observations behind {@code metric} in the window as the feed reported them (a flow: summed over
         * the days; a level: the latest reading; a ratio: its denominator's), or null when it never said.
         */
        Long sampleSize(Metric metric, LocalDate from, LocalDate to) {
            if (metric.agg() == Aggregation.DERIVED) {
                Long denominator = sampleSize(component(metric.denominator()), from, to);
                return denominator != null ? denominator : sampleSize(component(metric.numerator()), from, to);
            }
            if (metric.agg() == Aggregation.WINDOW_UNIQUE) {
                // the daily observations do not describe a distinct count over the whole window
                return null;
            }
            TreeMap<LocalDate, Long> series = samples.get(metric.key());
            if (series == null) {
                return null;
            }
            if (metric.agg() == Aggregation.LEVEL) {
                var latest = series.floorEntry(to);
                return latest == null ? null : latest.getValue();
            }
            if (to.isBefore(from)) {
                return null;
            }
            var window = series.subMap(from, true, to, true);
            return window.isEmpty() ? null : window.values().stream().mapToLong(Long::longValue).sum();
        }

        boolean hasRows(Metric metric) {
            return switch (metric.agg()) {
                case SUM, WINDOW_UNIQUE -> daily.containsKey(metric.key());
                case LEVEL -> levels.containsKey(metric.key());
                case DERIVED -> hasRows(component(metric.numerator())) && hasRows(component(metric.denominator()));
            };
        }

        private Metric component(String key) {
            return channel.metric(key).orElseThrow();
        }

        Double periodValue(Metric metric, LocalDate from, LocalDate to) {
            return switch (metric.agg()) {
                case SUM -> {
                    TreeMap<LocalDate, Double> series = daily.get(metric.key());
                    // a fixed window can end before it begins for a channel whose data stops short of it
                    if (series == null || to.isBefore(from)) {
                        yield null;
                    }
                    var window = series.subMap(from, true, to, true);
                    yield window.isEmpty() ? null : window.values().stream().mapToDouble(Double::doubleValue).sum();
                }
                case LEVEL -> levelAt(metric, to);
                case DERIVED -> ratio(periodValue(component(metric.numerator()), from, to),
                        periodValue(component(metric.denominator()), from, to));
                case WINDOW_UNIQUE -> windowUniqueValue(metric, from, to);
            };
        }

        /**
         * The value over an explicit range counting only settled days: like {@link #periodValue} but a day still
         * being restated is left out of a flow. A level is its latest reading at or before {@code to}; a
         * window-unique count is the stored one for a window of that length ending on {@code to}.
         */
        Double valueOver(Metric metric, LocalDate from, LocalDate to) {
            return switch (metric.agg()) {
                case SUM -> {
                    TreeMap<LocalDate, Double> series = daily.get(metric.key());
                    if (series == null || to.isBefore(from)) {
                        yield null;
                    }
                    Set<LocalDate> restating = provisional.getOrDefault(metric.key(), Set.of());
                    double sum = 0;
                    boolean any = false;
                    for (Map.Entry<LocalDate, Double> e : series.subMap(from, true, to, true).entrySet()) {
                        if (!restating.contains(e.getKey())) {
                            sum += e.getValue();
                            any = true;
                        }
                    }
                    yield any ? sum : null;
                }
                case LEVEL -> levelAt(metric, to);
                case DERIVED -> ratio(valueOver(component(metric.numerator()), from, to),
                        valueOver(component(metric.denominator()), from, to));
                case WINDOW_UNIQUE -> windowUniqueValue(metric, from, to);
            };
        }

        Double dayValue(Metric metric, LocalDate day) {
            return switch (metric.agg()) {
                case SUM, WINDOW_UNIQUE -> {
                    TreeMap<LocalDate, Double> series = daily.get(metric.key());
                    yield series == null ? null : series.get(day);
                }
                case LEVEL -> levelAt(metric, day);
                case DERIVED -> ratio(dayValue(component(metric.numerator()), day),
                        dayValue(component(metric.denominator()), day));
            };
        }

        /** The day of the latest snapshot of a level at or before {@code day} across its series, or null. */
        LocalDate latestDay(Metric metric, LocalDate day) {
            return levels.getOrDefault(metric.key(), Map.of()).values().stream()
                    .map(s -> s.floorKey(day)).filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
        }

        /** The first day this metric has any row (a derived metric: its numerator's), or null. */
        LocalDate firstDay(Metric metric) {
            return switch (metric.agg()) {
                case SUM, WINDOW_UNIQUE -> daily.containsKey(metric.key()) ? daily.get(metric.key()).firstKey() : null;
                case LEVEL -> levels.getOrDefault(metric.key(), Map.of()).values().stream()
                        .map(TreeMap::firstKey).min(Comparator.naturalOrder()).orElse(null);
                case DERIVED -> firstDay(component(metric.numerator()));
            };
        }

        private Double levelAt(Metric metric, LocalDate day) {
            Map<String, TreeMap<LocalDate, Double>> series = levels.get(metric.key());
            if (series == null) {
                return null;
            }
            if (metric.denominator() != null) {
                return weightedLevelAt(series, levels.get(metric.denominator()), day);
            }
            double total = 0;
            boolean any = false;
            for (TreeMap<LocalDate, Double> s : series.values()) {
                var entry = s.floorEntry(day);
                if (entry != null) {
                    total += entry.getValue();
                    any = true;
                }
            }
            return any ? total : null;
        }

        /**
         * An average-valued snapshot across connections: each connection's latest value weighted by its own latest
         * weight (rating average by rating count), so an app with 3 ratings cannot outvote one with 3,000. A plain
         * mean when any connection has no positive weight; a single connection is returned unchanged.
         */
        private static Double weightedLevelAt(Map<String, TreeMap<LocalDate, Double>> values,
                                              Map<String, TreeMap<LocalDate, Double>> weights, LocalDate day) {
            double weighted = 0;
            double weightSum = 0;
            double plain = 0;
            int n = 0;
            boolean allWeighted = true;
            for (Map.Entry<String, TreeMap<LocalDate, Double>> e : values.entrySet()) {
                var entry = e.getValue().floorEntry(day);
                if (entry == null) {
                    continue;
                }
                n++;
                plain += entry.getValue();
                var w = weights == null || weights.get(e.getKey()) == null ? null : weights.get(e.getKey()).floorEntry(day);
                if (w == null || w.getValue() <= 0) {
                    allWeighted = false;
                } else {
                    weighted += entry.getValue() * w.getValue();
                    weightSum += w.getValue();
                }
            }
            if (n == 0) {
                return null;
            }
            return allWeighted && weightSum > 0 ? weighted / weightSum : plain / n;
        }

        /** True when any row feeding {@code metric} for {@code day} is provisional. */
        boolean isProvisional(Metric metric, LocalDate day) {
            Set<LocalDate> days = new HashSet<>();
            collectProvisional(metric, day, day, days);
            return !days.isEmpty();
        }

        private void collectProvisional(Metric metric, LocalDate from, LocalDate to, Set<LocalDate> into) {
            if (metric.agg() == Aggregation.DERIVED) {
                collectProvisional(component(metric.numerator()), from, to, into);
                collectProvisional(component(metric.denominator()), from, to, into);
                return;
            }
            for (LocalDate day : provisional.getOrDefault(metric.key(), Set.of())) {
                if (!day.isBefore(from) && !day.isAfter(to)) {
                    into.add(day);
                }
            }
        }
    }

    private static Double ratio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || denominator == 0) {
            return null;
        }
        return numerator / denominator;
    }

    /**
     * @param partial the channel's settled data ends before a fixed window's last day: its flows cover fewer days
     *                than the previous window, so they carry no comparison with it
     * @param reportedBefore for targeted levels with no row in the lookback, the last day each was reported before
     *                       it (see {@link #snapshotsBeforeLookback})
     */
    private MetricView metricView(Context ctx, MetricCatalog.Channel channel, ChannelData data, Metric metric,
                                  Window flowWindow, boolean partial, Map<String, LocalDate> reportedBefore) {
        // A level is a snapshot, not a flow: its value is the latest snapshot at or before today whatever the
        // flow window (in a fixed window, at or before its last day), and its previous is the snapshot one window
        // earlier than that.
        LocalDate end = ctx.fixed() ? ctx.fixedTo : ctx.today;
        LocalDate asOf = metric.agg() == Aggregation.LEVEL ? data.latestDay(metric, end) : null;
        Window w = asOf != null ? windowEnding(ctx, asOf) : flowWindow;
        Double value = data.periodValue(metric, w.from(), w.to());
        Double previous = data.periodValue(metric, w.previousFrom(), w.previousTo());
        LocalDate firstDay = data.firstDay(metric);
        // A flow whose history starts inside the previous window has only part of it: the sum of a few days
        // against a full window is no comparison (it reads as +10000%), so treat it as having no previous data.
        // A window-unique count is stored whole for its window, so a late-starting history does not make it partial.
        boolean partialPrevious = previous != null && metric.agg() != Aggregation.LEVEL
                && metric.agg() != Aggregation.WINDOW_UNIQUE
                && firstDay != null && firstDay.isAfter(w.previousFrom());
        boolean flowShorterThanPrevious = partial && (metric.agg() == Aggregation.SUM
                || metric.agg() == Aggregation.DERIVED);
        if (partialPrevious || flowShorterThanPrevious) {
            previous = null;
        }
        Double change = value != null && previous != null ? value - previous : null;
        Double changePct = change != null && previous != 0 ? change / Math.abs(previous) : null;

        List<Point> series = new ArrayList<>();
        // The window's days, then any days after it that have only provisional data, flagged so a chart can
        // draw them differently. Totals and changes above use complete days only.
        int provisionalDays = 0;
        LocalDate seriesEnd = metric.agg() == Aggregation.LEVEL ? w.to()
                : ctx.fixed() ? ctx.fixedTo
                : (w.to().isAfter(ctx.yesterday) ? w.to() : ctx.yesterday);
        for (LocalDate day = w.from(); !day.isAfter(seriesEnd); day = day.plusDays(1)) {
            Double dayValue = data.dayValue(metric, day);
            boolean afterWindow = day.isAfter(w.to());
            // a level is carried forward every day, so only flows have provisional days to show
            if (afterWindow && (dayValue == null || metric.agg() == Aggregation.LEVEL)) {
                continue;
            }
            if (afterWindow) {
                provisionalDays++;
            }
            series.add(new Point(day, dayValue, afterWindow || data.isProvisional(metric, day)));
        }

        TargetView targetView = targetOf(ctx, channel, metric);
        // A feed that reports how many observations stand behind a number decides for itself: too few is
        // insufficient data. Otherwise the change detector judges.
        Long sample = data.sampleSize(metric, w.from(), w.to());
        boolean insufficient = sample != null && sample < MIN_SAMPLE;
        boolean noise = sample != null ? insufficient
                : value != null && previous != null && withinNormalVariation(ctx, data, metric, value, w);
        LocalDate historyStartsOn = previous == null
                ? (metric.agg() == Aggregation.WINDOW_UNIQUE ? data.firstWindowUniqueDay(metric, ctx.days) : firstDay)
                : null;
        // A snapshot the feed stopped sending says when it was last reported, and a targeted one is not judged: a
        // reading that old says where it was, not where it is. A window-unique count that cannot be read says why,
        // and is never rebuilt from the daily counts.
        LocalDate lastReported = asOf != null ? asOf : reportedBefore.get(metric.key());
        boolean stopped = stoppedArriving(lastReported, flowWindow.to());
        String note = stopped ? stoppedNote(channel, lastReported, flowWindow.to())
                : value == null ? windowUniqueReason(metric, w) : null;
        // A rate is divided over its denominator's total: the n of its interval, and its sample when the feed
        // reported none.
        Long divisor = null;
        if (metric.agg() == Aggregation.DERIVED
                && channel.metric(metric.denominator()).filter(c -> c.unit() == Unit.COUNT).isPresent()) {
            Double denominator = data.periodValue(channel.metric(metric.denominator()).orElseThrow(), w.from(), w.to());
            divisor = denominator == null ? null : Math.round(denominator);
        }
        Long statusSample = sample != null ? sample : divisor;
        TargetStatus targetStatus = stopped && targetView != null ? new TargetStatus(STOPPED, null)
                : targetStatus(metric, value, targetView, statusSample, divisor != null ? divisor : statusSample,
                        ctx.days);
        if (targetView != null) {
            targetView = targetView.withEffectiveValue(effectiveTarget(metric, targetView, ctx.days));
        }
        return new MetricView(metric.key(), metric.label(), metric.unit(), metric.good().name(), value, previous,
                change, changePct, noise, provisionalDays, targetView, series, historyStartsOn, asOf, insufficient, metric.funnelOrder(),
                metric.agg().name(), targetStatus.status(), targetStatus.missPct(), statusSample, metric.area(),
                metric.description(), metric.headline(), metric.basis(), metric.definition(), note,
                targetStatus.closeToTarget(), STOPPED.equals(targetStatus.status()) ? lastReported : null);
    }

    /**
     * Where {@code value} stands against its target. NO_TARGET with none; TOO_EARLY with no value, or for a rate
     * (a PERCENT metric or a ratio) resting on fewer than {@link #MIN_SAMPLE} observations; ON_TRACK when met in
     * the target's direction; WATCH when it misses by no more than the watch band (default
     * {@link #DEFAULT_WATCH_BAND_PCT}%) of the target, or when it is a share over a known {@code n} whose 95% Wilson
     * interval still contains the target (4 of 32 against 20% is a miss chance alone makes one time in five);
     * otherwise OFF_TRACK. A count or currency total is compared
     * with its target prorated from the window the target declares (feed targets only; none means compared as stated) to {@code windowDays}.
     *
     * @param sample the observations behind the value, for the {@link #MIN_SAMPLE} gate
     * @param n      what a share was divided over (its denominator's total), for the interval and the same gate;
     *               null when unknown
     */
    static TargetStatus targetStatus(Metric metric, Double value, TargetView target, Long sample, Long n,
                                     int windowDays) {
        if (target == null) {
            return new TargetStatus(NO_TARGET, null);
        }
        boolean rate = metric.unit() == Unit.PERCENT || metric.agg() == Aggregation.DERIVED;
        if (value == null || (rate && ((sample != null && sample < MIN_SAMPLE) || (n != null && n < MIN_SAMPLE)))) {
            return new TargetStatus(TOO_EARLY, null);
        }
        double expected = effectiveTarget(metric, target, windowDays);
        boolean up = "UP".equals(target.direction());
        if (up ? value >= expected : value <= expected) {
            return new TargetStatus(ON_TRACK, 0.0);
        }
        if (expected <= 0) {
            return new TargetStatus(OFF_TRACK, null);
        }
        double miss = up ? (expected - value) / expected : (value - expected) / expected;
        double band = (target.watchBandPct() != null ? target.watchBandPct()
                : metric.watchBandPct() != null ? metric.watchBandPct() : DEFAULT_WATCH_BAND_PCT) / 100;
        if (miss <= band + 1e-9) {
            return new TargetStatus(WATCH, miss, miss <= band / 2 + 1e-9);
        }
        boolean couldBeChance = metric.unit() == Unit.PERCENT && n != null && n > 0
                && value >= 0 && value <= 1 && expected <= 1 && wilsonContains(value, n, expected);
        return new TargetStatus(couldBeChance ? WATCH : OFF_TRACK, miss, false);
    }

    /** Whether {@code target} lies inside the 95% Wilson interval of {@code rate} (0 to 1) over {@code n}. */
    static boolean wilsonContains(double rate, long n, double target) {
        WilsonInterval.Range range = WilsonInterval.of(rate * n, n);
        return target >= range.low() && target <= range.high();
    }

    /**
     * A snapshot (a level) the feed stopped sending while the rest of its channel kept arriving: last reported more
     * than {@link #LAGGING_DAYS} days before the channel's data ends. A targeted one is {@link #STOPPED}, not on or
     * off target, and the alerts report the stop.
     */
    public static boolean stoppedArriving(LocalDate lastReported, LocalDate channelThrough) {
        return lastReported != null && channelThrough != null
                && lastReported.isBefore(channelThrough.minusDays(LAGGING_DAYS));
    }

    private static String stoppedNote(MetricCatalog.Channel channel, LocalDate asOf, LocalDate channelThrough) {
        return "Last reported " + asOf + "; the rest of the " + channel.label() + " data runs to " + channelThrough
                + ".";
    }

    /**
     * The target the value is compared with. Only a count or currency total whose target declares a window
     * (a feed target) is prorated to {@code windowDays}; a Conductor-owned target has no window and is
     * compared as stated.
     */
    static double effectiveTarget(Metric metric, TargetView target, int windowDays) {
        if (target.window() != null && !target.window().isBlank() && metric.agg() == Aggregation.SUM
                && (metric.unit() == Unit.COUNT || metric.unit() == Unit.CURRENCY)) {
            return target.value() * windowDays / targetWindowDays(target.window());
        }
        return target.value();
    }

    private static int targetWindowDays(String window) {
        if (window != null && window.matches("[0-9]{1,3}d")) {
            int days = Integer.parseInt(window.substring(0, window.length() - 1));
            if (days > 0) {
                return days;
            }
        }
        return DEFAULT_TARGET_WINDOW_DAYS;
    }

    /**
     * True when the current window's move is not material to {@link MetricsChangeDetector}. The windows
     * before the current one are fed through as consecutive periods (oldest first, skipping any with no
     * data) so the detector's baseline reflects this metric's own variation; thresholds are the detector's
     * defaults, not ours.
     */
    private boolean withinNormalVariation(Context ctx, ChannelData data, Metric metric, double current,
                                          Window w) {
        MetricSpec spec = new MetricSpec(metric.key(), metric.label(), metric.unit().name(),
                com.conductor.integration.Aggregation.LAST, "value", null, null, null, Direction.NEUTRAL,
                null, null, null);
        DigestSpec digest = new DigestSpec(null, null, List.of(spec), List.of(), null, null);

        MetricsBaseline baseline = null;
        for (int k = HISTORY_PERIODS; k >= 1; k--) {
            LocalDate from = w.from().minusDays((long) k * ctx.days);
            LocalDate to = from.plusDays(ctx.days - 1L);
            if (from.isBefore(ctx.historyStart)) {
                continue;
            }
            Double value = data.periodValue(metric, from, to);
            if (value == null) {
                continue;
            }
            baseline = changeDetector.detect(snapshot(metric, value), baseline, digest, to.toString(),
                    0).updatedBaseline();
        }
        return !changeDetector.detect(snapshot(metric, current), baseline, digest, w.to().toString(), 0)
                .metricChanges().get(0).material();
    }

    private static MetricsSnapshot snapshot(Metric metric, double value) {
        return new MetricsSnapshot(Map.of(metric.key(), value), Map.of());
    }

    // ---- funnel -------------------------------------------------------------------------------------

    /**
     * The window every funnel stage is computed over: it ends at the earliest data-complete day of the channels that
     * contribute an available stage (or, when none does, of the channels that have any data).
     */
    private Window funnelWindow(Context ctx, Map<MetricChannel, ChannelBuild> builds, Set<MetricChannel> lagging) {
        Set<MetricChannel> contributing = new HashSet<>();
        for (MetricCatalog.FunnelStage stage : MetricCatalog.funnel(ctx.productChannel)) {
            ChannelBuild build = builds.get(stage.channel());
            if (build != null && build.hasData() && !lagging.contains(stage.channel()) && build.metric(stage.metric())
                    .filter(m -> m.value() != null || m.previous() != null).isPresent()) {
                contributing.add(stage.channel());
            }
        }
        List<ChannelBuild> sources = contributing.isEmpty()
                ? builds.values().stream()
                        .filter(b -> b.hasData() && !lagging.contains(b.channel.channel())).toList()
                : contributing.stream().map(builds::get).toList();
        LocalDate end = sources.stream().map(b -> b.window.to()).min(Comparator.naturalOrder())
                .orElse(ctx.fallbackEnd);
        return windowEnding(ctx, end);
    }

    /**
     * Finds the lagging channels and marks each STALE. Lagging wins over MISMATCH: the mismatch compares settled
     * days that the channel no longer reports.
     */
    private Set<MetricChannel> markLagging(Context ctx, Map<MetricChannel, ChannelBuild> builds) {
        Set<MetricChannel> lagging = laggingChannels(ctx, builds);
        lagging.forEach(c -> builds.get(c).status = "STALE");
        return lagging;
    }

    /**
     * The channels whose data ends more than {@link #LAGGING_DAYS} days before the freshest channel's. Only
     * channels that are connected and have data count, so a channel nobody connected is not "behind".
     */
    private Set<MetricChannel> laggingChannels(Context ctx, Map<MetricChannel, ChannelBuild> builds) {
        Map<MetricChannel, LocalDate> ends = new LinkedHashMap<>();
        for (ChannelBuild b : builds.values()) {
            if (b.live() && b.hasData()) {
                // a snapshot-only channel is "complete through today": cap at yesterday like the flows
                LocalDate end = b.window.to().isAfter(ctx.yesterday) ? ctx.yesterday : b.window.to();
                ends.put(b.channel.channel(), end);
            }
        }
        Set<MetricChannel> lagging = new HashSet<>();
        ends.values().stream().max(Comparator.naturalOrder()).ifPresent(freshest ->
                ends.forEach((channel, end) -> {
                    if (end.isBefore(freshest.minusDays(LAGGING_DAYS))) {
                        lagging.add(channel);
                    }
                }));
        return lagging;
    }

    private static final java.time.format.DateTimeFormatter DAY_LABEL =
            java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private static String noDataFor(ChannelBuild build, Window w) {
        return "No " + build.channel.label() + " data for " + dayLabel(w.from()) + " \u2013 " + dayLabel(w.to());
    }

    private static String dayLabel(LocalDate day) {
        return DAY_LABEL.format(day);
    }

    /** @param shortened a fixed window the funnel's days stop short of: a flow has fewer days than its previous window */
    private FunnelStageView funnelStage(MetricCatalog.FunnelStage stage, ChannelBuild build, Window w,
                                        boolean lagging, boolean shortened) {
        Optional<MetricView> shown = build == null ? Optional.empty() : build.metric(stage.metric());
        Optional<Metric> catalogMetric = build == null ? Optional.empty() : build.channel.metric(stage.metric());
        if (build == null || build.data == null) {
            // not connected: tell them to connect
            return new FunnelStageView(stage.key(), stage.label(), null, null, null, stage.channel().name(), false,
                    stage.unavailableReason(), build == null ? null : build.window.to(), stage.channel().name(),
                    stage.conversionFrom());
        }
        if (lagging) {
            return new FunnelStageView(stage.key(), stage.label(), null, null, null, stage.channel().name(), false,
                    "No " + build.channel.label() + " data since " + dayLabel(build.window.to()),
                    build.window.to(), stage.channel().name(), stage.conversionFrom());
        }
        if (shown.isEmpty() || catalogMetric.isEmpty()
                || (shown.get().value() == null && shown.get().previous() == null)) {
            // Connected and reporting something else: a "Connect ..." hint would be wrong, so say there is no data.
            // Other hints (e.g. set a conversion event) are about setup and stay.
            String reason = build.hasData() && stage.unavailableReason().startsWith("Connect")
                    ? noDataFor(build, w) : stage.unavailableReason();
            // A distinct count with no value for this window's length says why, not "no data"
            String windowReason = catalogMetric.map(m -> windowUniqueReason(m, w)).orElse(null);
            boolean unsupportedLength = catalogMetric.filter(m -> !m.supportsWindowOf(windowDays(w))).isPresent();
            if (windowReason != null && (unsupportedLength || build.hasData())) {
                reason = windowReason;
            }
            return new FunnelStageView(stage.key(), stage.label(), null, null, null, stage.channel().name(), false,
                    reason, build.window.to(), stage.channel().name(), stage.conversionFrom());
        }
        Metric metric = catalogMetric.get();
        Double value = build.data.periodValue(metric, w.from(), w.to());
        Double previous = build.data.periodValue(metric, w.previousFrom(), w.previousTo());
        LocalDate firstDay = build.data.firstDay(metric);
        if (previous != null && metric.agg() != Aggregation.LEVEL && metric.agg() != Aggregation.WINDOW_UNIQUE
                && firstDay != null && firstDay.isAfter(w.previousFrom())) {
            previous = null; // only partly covered by history: no comparison
        }
        if (shortened && (metric.agg() == Aggregation.SUM || metric.agg() == Aggregation.DERIVED)) {
            previous = null; // fewer days than the previous window: no comparison
        }
        if (value == null && windowUniqueReason(metric, w) != null) {
            // a distinct count has no value for this window; a previous one alone would read as a stage with no number
            return new FunnelStageView(stage.key(), stage.label(), null, null, null, stage.channel().name(), false,
                    windowUniqueReason(metric, w), w.to(), stage.channel().name(), stage.conversionFrom());
        }
        if (value == null && previous == null) {
            // connected, with data, just none in these days: say so rather than telling them to connect
            return new FunnelStageView(stage.key(), stage.label(), null, null, null, stage.channel().name(), false,
                    noDataFor(build, w), w.to(), stage.channel().name(), stage.conversionFrom());
        }
        Double change = value != null && previous != null ? value - previous : null;
        // Same sum over the window's days for either basis; a cohort stage's days are cohort days, so the value is
        // the people who first opened in the window, followed to now.
        return new FunnelStageView(stage.key(), stage.label(), value, previous, change,
                stage.channel().name(), true, null, w.to(), stage.channel().name(), stage.conversionFrom(),
                metric.basis());
    }

    private static long windowDays(Window w) {
        return java.time.temporal.ChronoUnit.DAYS.between(w.from(), w.to()) + 1;
    }

    /**
     * Why a window-unique metric has no value for {@code w}: its length is not one the connector stores, or the
     * stored count for it has not arrived. Null for any other metric.
     */
    static String windowUniqueReason(Metric metric, Window w) {
        if (metric.agg() != Aggregation.WINDOW_UNIQUE) {
            return null;
        }
        long days = windowDays(w);
        return metric.supportsWindowOf(days) ? "No unique " + metric.label().toLowerCase(java.util.Locale.ROOT)
                + " count is stored for these " + days + " days yet." : metric.unsupportedWindowMessage();
    }

    /** Keeps {@code conversionFromKey} only where both the stage and the stage it converts from are available. */
    private static List<FunnelStageView> withConversions(List<FunnelStageView> stages) {
        Map<String, FunnelStageView> byKey = new HashMap<>();
        stages.forEach(s -> byKey.put(s.key(), s));
        return stages.stream().map(s -> {
            FunnelStageView from = s.conversionFromKey() == null ? null : byKey.get(s.conversionFromKey());
            boolean usable = s.available() && from != null && from.available();
            return new FunnelStageView(s.key(), s.label(), s.value(), s.previous(), s.change(), s.source(),
                    s.available(), s.unavailableReason(), s.dataCompleteThrough(), s.path(),
                    usable ? s.conversionFromKey() : null, s.basis());
        }).toList();
    }

    // ---- dimension tables ---------------------------------------------------------------------------

    private List<DimensionTable> dimensions(Context ctx, MetricCatalog.Channel channel, ChannelBuild build,
                                            java.util.function.Function<Dimension, TableOptions> options) {
        Set<String> connectionIds = new HashSet<>();
        build.connections.forEach(c -> connectionIds.add(c.id()));
        List<DimensionTable> tables = new ArrayList<>();
        // One read serves every table of the channel: they differ by key prefix and columns, not by the rows' range,
        // so a channel's cost is one query however many breakdowns it offers.
        List<String> sources = channel.dimensions().stream().flatMap(d -> d.columns().stream())
                .map(Column::source).filter(s -> s != null).distinct().toList();
        Window w = build.window;
        List<ChannelMetricDaily> rows = sources.isEmpty() ? List.of()
                : dailyRepository.findByProjectIdAndChannelAndMetricInAndDayBetween(ctx.projectId,
                        channel.channel().name(), sources, ctx.historyStart.isBefore(w.from()) ? ctx.historyStart : w.from(),
                        w.to().isBefore(ctx.today) ? ctx.today : w.to());
        for (Dimension dimension : channel.dimensions()) {
            DimensionTable table = dimensionTable(channel, dimension, rows, connectionIds, build.window,
                    options.apply(dimension));
            // the in-app tables are optional breakdowns: a feed with no sources or campaigns shows none
            if (channel.channel() != MetricChannel.PRODUCT || !table.rows().isEmpty()) {
                tables.add(table);
            }
        }
        return tables;
    }

    /** One table from the channel's shared dimension rows: this dimension's key prefix and the connections behind the channel. */
    private DimensionTable dimensionTable(MetricCatalog.Channel channel, Dimension dimension,
                                          List<ChannelMetricDaily> channelRows, Set<String> connectionIds, Window w,
                                          TableOptions options) {
        List<ChannelMetricDaily> rows = channelRows.stream()
                .filter(r -> !r.getDimensionKey().isEmpty() && connectionIds.contains(r.getConnectionId())
                        && (dimension.keyPrefix() == null || r.getDimensionKey().startsWith(dimension.keyPrefix())))
                .toList();

        // dimension key -> column key -> value
        Map<String, Map<String, Double>> byKey = new LinkedHashMap<>();
        for (Column column : dimension.columns()) {
            if (column.agg() == Aggregation.DERIVED) {
                continue;
            }
            Map<String, Double> perKey = column.agg() == Aggregation.LEVEL
                    ? latestPerSeries(rows, column.source())
                    : sumPerKey(rows, column.source(), w.from(), w.to());
            perKey.forEach((key, value) -> byKey.computeIfAbsent(key, k -> new HashMap<>()).put(column.key(), value));
        }
        for (Map<String, Double> values : byKey.values()) {
            for (Column column : dimension.columns()) {
                if (column.agg() == Aggregation.DERIVED) {
                    Double v = ratio(values.get(column.numerator()), values.get(column.denominator()));
                    if (v != null) {
                        values.put(column.key(), v);
                    }
                }
            }
        }

        Map<String, Map<String, Object>> states = dimension.keyPrefix() == null
                ? Map.of() : stateStore.find(connectionIds, dimension.keyPrefix());
        String sortKey = options.sortKey() != null
                && dimension.columns().stream().anyMatch(c -> c.key().equals(options.sortKey()))
                ? options.sortKey() : dimension.columns().get(0).key();
        List<DimensionRowView> out = byKey.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Map<String, Double>> e) ->
                        e.getValue().getOrDefault(sortKey, Double.NEGATIVE_INFINITY)).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(options.limit())
                .map(e -> toRow(channel.channel(), e.getKey(), e.getValue(), dimension, states.get(e.getKey())))
                .toList();
        return new DimensionTable(dimension.key(), dimension.label(),
                dimension.columns().stream().map(c -> new ColumnView(c.key(), c.label(), c.unit())).toList(), out);
    }

    private DimensionRowView toRow(MetricChannel channel, String dimensionKey, Map<String, Double> values,
                                   Dimension dimension, Map<String, Object> state) {
        String key = dimensionKey;
        String label = dimensionKey;
        if (channel == MetricChannel.ADS) {
            // campaign:{id}:{name}; the name may itself contain colons, so split on the first two only
            // ad:{adGroupId}:{adId}:{name}: an ad id is only unique within its ad group, so the row key keeps both
            String[] parts = dimensionKey.split(":", 3);
            String[] adParts = dimensionKey.startsWith("ad:") ? dimensionKey.split(":", 4) : null;
            if (dimensionKey.startsWith(MetricCatalog.SEARCH_TERM_PREFIX)) {
                // searchterm:{campaignId}:{term}: the same term can run in several campaigns, so the row key keeps both
                key = dimensionKey.substring(MetricCatalog.SEARCH_TERM_PREFIX.length());
                label = parts.length == 3 && !parts[2].isBlank() ? parts[2] : key;
            } else if (adParts != null && adParts.length == 4) {
                key = adParts[1] + ":" + adParts[2];
                label = adParts[3].isBlank() ? key : adParts[3];
            } else if (parts.length == 3) {
                key = parts[1];
                label = parts[2].isBlank() ? parts[1] : parts[2];
            }
        } else if (channel == MetricChannel.STORE) {
            if (dimensionKey.startsWith(MetricCatalog.STORE_SOURCE_PREFIX)) {
                key = dimensionKey.substring(MetricCatalog.STORE_SOURCE_PREFIX.length());
                label = MetricCatalog.storeSourceLabel(key);
            }
        } else if (channel == MetricChannel.SOCIAL) {
            label = platformRegistry.labelOf(dimensionKey);
        } else if (channel == MetricChannel.PRODUCT && dimension.keyPrefix() != null) {
            // source:{name}, campaign:{id}:{name} or keyword:{id}:{text}; the last two show the name, keyed by id
            String rest = dimensionKey.substring(dimension.keyPrefix().length());
            if (dimension.keyPrefix().equals("campaign:") || dimension.keyPrefix().equals("keyword:")) {
                String[] parts = rest.split(":", 2);
                key = parts[0];
                label = parts.length == 2 && !parts[1].isBlank() ? parts[1] : parts[0];
            } else {
                key = rest;
                label = rest;
            }
        }
        List<Double> ordered = dimension.columns().stream().map(c -> values.get(c.key())).toList();
        return new DimensionRowView(key, label, ordered, state == null || state.isEmpty() ? null : state);
    }

    private static Map<String, Double> sumPerKey(List<ChannelMetricDaily> rows, String metric, LocalDate from,
                                                 LocalDate to) {
        Map<String, Double> sums = new LinkedHashMap<>();
        for (ChannelMetricDaily row : rows) {
            if (row.getMetric().equals(metric) && !row.getDay().isBefore(from) && !row.getDay().isAfter(to)) {
                sums.merge(row.getDimensionKey(), row.getValue().doubleValue(), Double::sum);
            }
        }
        return sums;
    }

    /** Latest value per (connection, key) in the window, summed across connections. */
    private static Map<String, Double> latestPerSeries(List<ChannelMetricDaily> rows, String metric) {
        Map<String, ChannelMetricDaily> latest = new HashMap<>();
        for (ChannelMetricDaily row : rows) {
            if (!row.getMetric().equals(metric)) {
                continue;
            }
            latest.merge(row.getConnectionId() + "|" + row.getDimensionKey(), row,
                    (a, b) -> b.getDay().isAfter(a.getDay()) ? b : a);
        }
        Map<String, Double> sums = new LinkedHashMap<>();
        for (ChannelMetricDaily row : latest.values()) {
            sums.merge(row.getDimensionKey(), row.getValue().doubleValue(), Double::sum);
        }
        return sums;
    }
}
