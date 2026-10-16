package com.conductor.marketing.overview;

import com.conductor.entity.ChannelMetricDaily;
import com.conductor.entity.ChannelMetricTarget;
import com.conductor.entity.Connection;
import com.conductor.exception.BusinessException;
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
import com.conductor.marketing.overview.MetricCatalog.Dimension;
import com.conductor.marketing.overview.MetricCatalog.Metric;
import com.conductor.marketing.overview.MetricCatalog.Unit;
import com.conductor.repository.ChannelMetricDailyRepository;
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
 * average of daily ratios.
 *
 * <p><b>Noise.</b> {@link MetricsChangeDetector} judges each metric: the windows before the current one are
 * replayed through it as consecutive periods to build its baseline, then the current window is checked
 * against it with the detector's own gates (absolute, relative, statistical) and defaults. A change that
 * does not clear them is {@code noise}.
 */
@Service
public class MarketingOverviewQueryService {

    /** A feed that has not succeeded in this many of its own intervals is stale. */
    static final int STALE_INTERVALS = 2;
    static final int DIMENSION_ROWS = 50;
    static final int TOP_POSTS = 5;
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

    public record TargetView(double value, String direction) {}

    public record MetricView(String metric, String label, Unit unit, String goodDirection, Double value, Double previous, Double change,
                             Double changePct, boolean noise, int provisionalDays, TargetView target,
                             List<Point> series, LocalDate historyStartsOn, LocalDate asOf) {}

    public record ChannelView(String channel, String label, String status, OffsetDateTime lastFetchedAt,
                              List<ConnectionView> connections, ConnectHint connectHint,
                              List<MetricView> metrics, LocalDate dataCompleteThrough) {}

    public record FunnelStageView(String key, String label, Double value, Double previous, Double change,
                                  String source, boolean available, String unavailableReason,
                                  LocalDate dataCompleteThrough, String path, String conversionFromKey) {}

    public record Overview(Window window, List<FunnelStageView> funnel, List<ChannelView> channels) {}

    public record ColumnView(String key, String label, Unit unit) {}

    public record DimensionRowView(String key, String label, List<Double> values) {}

    public record DimensionTable(String key, String label, List<ColumnView> columns, List<DimensionRowView> rows) {}

    public record ChannelDetail(Window window, ChannelView channel, List<DimensionTable> dimensions,
                                List<MarketingInsightsQueryService.Post> topPosts) {}

    private final ChannelMetricDailyRepository dailyRepository;
    private final ChannelMetricTargetRepository targetRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectorFeedRepository feedRepository;
    private final MarketingInsightsQueryService insightsQueryService;
    private final PublishPlatformRegistry platformRegistry;
    private final MetricsChangeDetector changeDetector = new MetricsChangeDetector();

    public MarketingOverviewQueryService(ChannelMetricDailyRepository dailyRepository,
                                         ChannelMetricTargetRepository targetRepository,
                                         ConnectionRepository connectionRepository,
                                         ConnectorFeedRepository feedRepository,
                                         MarketingInsightsQueryService insightsQueryService,
                                         PublishPlatformRegistry platformRegistry) {
        this.dailyRepository = dailyRepository;
        this.targetRepository = targetRepository;
        this.connectionRepository = connectionRepository;
        this.feedRepository = feedRepository;
        this.insightsQueryService = insightsQueryService;
        this.platformRegistry = platformRegistry;
    }

    // ---- reads --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Overview overview(String projectId, String windowRaw) {
        return overview(projectId, parseWindow(windowRaw, Set.of(7, 28)), LocalDate.now(ZoneOffset.UTC),
                OffsetDateTime.now());
    }

    /** Package-visible so a test can pin "today" and "now". */
    Overview overview(String projectId, int days, LocalDate today, OffsetDateTime now) {
        Context ctx = context(projectId, days, today, now);
        Map<MetricChannel, ChannelBuild> builds = new LinkedHashMap<>();
        for (MetricCatalog.Channel channel : MetricCatalog.channels()) {
            builds.put(channel.channel(), build(ctx, channel));
        }
        List<FunnelStageView> stages = new ArrayList<>();
        for (MetricCatalog.FunnelStage stage : MetricCatalog.funnel()) {
            stages.add(funnelStage(stage, builds.get(stage.channel())));
        }
        List<FunnelStageView> funnel = withConversions(stages);
        List<ChannelView> channels = builds.values().stream()
                .map(b -> b.view(true))
                .toList();
        // The overall range spans the windows of the channels that have data, which differ by how far each is
        // data-complete. With none, there is no "data through" date to claim: both ends are null.
        List<ChannelBuild> withData = builds.values().stream().filter(ChannelBuild::hasData).toList();
        Window overall;
        if (withData.isEmpty()) {
            Window empty = windowEnding(ctx, ctx.fallbackEnd);
            overall = new Window(empty.from(), null, empty.previousFrom(), empty.previousTo(), null);
        } else {
            overall = new Window(
                    withData.stream().map(b -> b.window.from()).min(Comparator.naturalOrder()).orElseThrow(),
                    withData.stream().map(b -> b.window.to()).max(Comparator.naturalOrder()).orElseThrow(),
                    withData.stream().map(b -> b.window.previousFrom()).min(Comparator.naturalOrder()).orElseThrow(),
                    withData.stream().map(b -> b.window.previousTo()).max(Comparator.naturalOrder()).orElseThrow(),
                    withData.stream().map(b -> b.window.to()).min(Comparator.naturalOrder()).orElseThrow());
        }
        return new Overview(overall, funnel, channels);
    }

    @Transactional(readOnly = true)
    public ChannelDetail channel(String projectId, String channelRaw, String windowRaw) {
        MetricCatalog.Channel channel = requireChannel(channelRaw);
        int days = parseWindow(windowRaw, Set.of(7, 28, 90));
        return channel(projectId, channel, days, LocalDate.now(ZoneOffset.UTC), OffsetDateTime.now());
    }

    ChannelDetail channel(String projectId, MetricCatalog.Channel channel, int days, LocalDate today,
                          OffsetDateTime now) {
        Context ctx = context(projectId, days, today, now);
        ChannelBuild build = build(ctx, channel);
        List<DimensionTable> dimensions = build.live() ? dimensions(ctx, channel, build) : List.of();
        List<MarketingInsightsQueryService.Post> topPosts = channel.channel() == MetricChannel.SOCIAL
                ? insightsQueryService.topPosts(projectId, days, TOP_POSTS)
                : List.of();
        return new ChannelDetail(build.window, build.view(false), dimensions, topPosts);
    }

    // ---- targets ------------------------------------------------------------------------------------

    /** Sets (replacing any existing) the target for a metric; {@code directionRaw} defaults to the metric's good direction. */
    @Transactional
    public TargetView setTarget(String projectId, String channelRaw, String metricKey, Double target,
                                String directionRaw, String updatedBy) {
        MetricCatalog.Channel channel = requireChannel(channelRaw);
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
        Metric metric = requireMetric(channel, metricKey);
        targetRepository.findByProjectIdAndChannelAndMetric(projectId, channel.channel().name(), metric.key())
                .ifPresent(targetRepository::delete);
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

    static int parseWindow(String raw, Set<Integer> allowed) {
        String normalized = raw == null || raw.isBlank() ? "28d" : raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("d")) {
            try {
                int days = Integer.parseInt(normalized.substring(0, normalized.length() - 1));
                if (allowed.contains(days)) {
                    return days;
                }
            } catch (NumberFormatException ignored) {
                // falls through to the 400 below
            }
        }
        throw new BusinessException("Unknown window '" + raw + "'; expected "
                + allowed.stream().sorted().map(d -> d + "d").toList());
    }

    // ---- context ------------------------------------------------------------------------------------

    /** Everything one request shares across channels. */
    private final class Context {
        final String projectId;
        final int days;
        final LocalDate yesterday;
        final LocalDate today;
        /** Where a window ends when a channel has no settled data. */
        final LocalDate fallbackEnd;
        final LocalDate historyStart;
        final OffsetDateTime now;
        final List<Connection> connections;
        final List<ConnectorFeed> feeds;
        final Map<String, ChannelMetricTarget> targets;

        Context(String projectId, int days, LocalDate today, OffsetDateTime now) {
            this.projectId = projectId;
            this.days = days;
            this.now = now;
            LocalDate to = today.minusDays(1);
            this.yesterday = to;
            this.today = today;
            // Not yesterday: the UTC day before today is already the user's local today in the Americas, and a
            // window must never end on a day that is still going. Two days back is complete everywhere.
            this.fallbackEnd = today.minusDays(2);
            int lookback = Math.max(2 * days, Math.min(days * (HISTORY_PERIODS + 1), MAX_HISTORY_DAYS));
            this.historyStart = to.minusDays(lookback - 1L);
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
        }
    }

    private Context context(String projectId, int days, LocalDate today, OffsetDateTime now) {
        return new Context(projectId, days, today, now);
    }

    // ---- channel build ------------------------------------------------------------------------------

    private final class ChannelBuild {
        final MetricCatalog.Channel channel;
        final String status;
        final OffsetDateTime lastFetchedAt;
        final List<ConnectionView> connections;
        final ConnectHint connectHint;
        final List<MetricView> metrics;
        final Window window;

        ChannelBuild(MetricCatalog.Channel channel, String status, OffsetDateTime lastFetchedAt,
                     List<ConnectionView> connections, ConnectHint connectHint, List<MetricView> metrics,
                     Window window) {
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
            return status.equals("LIVE") || status.equals("STALE");
        }

        Optional<MetricView> metric(String key) {
            return metrics.stream().filter(m -> m.metric().equals(key)).findFirst();
        }

        ChannelView view(boolean headlineOnly) {
            List<MetricView> shown = headlineOnly ? metrics.stream().limit(channel.headline()).toList() : metrics;
            return new ChannelView(channel.channel().name(), channel.label(), status, lastFetchedAt, connections,
                    connectHint, shown, window.to());
        }
    }

    private ChannelBuild build(Context ctx, MetricCatalog.Channel channel) {
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
        Window window = windowEnding(ctx, dataCompleteThrough(ctx, channel, rows));
        ChannelData data = new ChannelData(channel, rows);
        List<MetricView> metrics = new ArrayList<>();
        for (Metric metric : channel.visibleMetrics()) {
            if (metric.optional() && !data.hasRows(metric)) {
                continue;
            }
            metrics.add(metricView(ctx, channel, data, metric, window));
        }
        return new ChannelBuild(channel, anyLive ? "LIVE" : "STALE", lastFetchedAt, connectionViews, hint, metrics, window);
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

    private static Window windowEnding(Context ctx, LocalDate to) {
        LocalDate from = to.minusDays(ctx.days - 1L);
        return new Window(from, to, from.minusDays(ctx.days), from.minusDays(1), to);
    }

    /**
     * The latest day with non-provisional data on every connection that has any: a source that restates
     * (Search Console lags about three days) must not have its short window compared with a full previous
     * one. The slowest connection wins. Only flows count: a snapshot (a level such as followers) is a
     * point-in-time reading with nothing to restate, so it never holds the date back. A channel with only
     * snapshots is as current as today; one with no settled data at all ends at {@code fallbackEnd}.
     */
    private static LocalDate dataCompleteThrough(Context ctx, MetricCatalog.Channel channel,
                                                 List<ChannelMetricDaily> rows) {
        Map<String, LocalDate> latestByConnection = new HashMap<>();
        boolean anySnapshot = false;
        for (ChannelMetricDaily row : rows) {
            Metric metric = channel.metric(row.getMetric()).orElse(null);
            if (metric == null) {
                continue;
            }
            if (metric.agg() == Aggregation.LEVEL) {
                anySnapshot = true;
            } else if (!row.isProvisional() && !row.getDay().isAfter(ctx.yesterday)) {
                latestByConnection.merge(row.getConnectionId(), row.getDay(), (a, b) -> b.isAfter(a) ? b : a);
            }
        }
        return latestByConnection.values().stream().min(Comparator.naturalOrder())
                .orElse(anySnapshot ? ctx.today : ctx.fallbackEnd);
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

        ChannelData(MetricCatalog.Channel channel, List<ChannelMetricDaily> rows) {
            this.channel = channel;
            for (ChannelMetricDaily row : rows) {
                Metric metric = channel.metric(row.getMetric()).orElse(null);
                if (metric == null) {
                    continue;
                }
                if (metric.agg() != Aggregation.LEVEL && !metric.fromDimensions()
                        && !row.getDimensionKey().isEmpty()) {
                    // a total metric: its per-dimension rows (loaded for the channel's level series) would double count
                    continue;
                }
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
            }
        }

        boolean hasRows(Metric metric) {
            return switch (metric.agg()) {
                case SUM -> daily.containsKey(metric.key());
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
                    if (series == null) {
                        yield null;
                    }
                    var window = series.subMap(from, true, to, true);
                    yield window.isEmpty() ? null : window.values().stream().mapToDouble(Double::doubleValue).sum();
                }
                case LEVEL -> levelAt(metric, to);
                case DERIVED -> ratio(periodValue(component(metric.numerator()), from, to),
                        periodValue(component(metric.denominator()), from, to));
            };
        }

        Double dayValue(Metric metric, LocalDate day) {
            return switch (metric.agg()) {
                case SUM -> {
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
                case SUM -> daily.containsKey(metric.key()) ? daily.get(metric.key()).firstKey() : null;
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

    private MetricView metricView(Context ctx, MetricCatalog.Channel channel, ChannelData data, Metric metric,
                                  Window flowWindow) {
        // A level is a snapshot, not a flow: its value is the latest snapshot at or before today whatever the
        // flow window, and its previous is the snapshot one window earlier than that.
        LocalDate asOf = metric.agg() == Aggregation.LEVEL ? data.latestDay(metric, ctx.today) : null;
        Window w = asOf != null ? windowEnding(ctx, asOf) : flowWindow;
        Double value = data.periodValue(metric, w.from(), w.to());
        Double previous = data.periodValue(metric, w.previousFrom(), w.previousTo());
        Double change = value != null && previous != null ? value - previous : null;
        Double changePct = change != null && previous != 0 ? change / Math.abs(previous) : null;

        List<Point> series = new ArrayList<>();
        // The window's days, then any days after it that have only provisional data, flagged so a chart can
        // draw them differently. Totals and changes above use complete days only.
        int provisionalDays = 0;
        LocalDate seriesEnd = metric.agg() == Aggregation.LEVEL ? w.to()
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

        ChannelMetricTarget target = ctx.targets.get(channel.channel().name() + "/" + metric.key());
        TargetView targetView = target == null ? null
                : new TargetView(target.getTarget().doubleValue(), target.getDirection());
        boolean noise = value != null && previous != null && withinNormalVariation(ctx, data, metric, value, w);
        LocalDate historyStartsOn = previous == null ? data.firstDay(metric) : null;
        return new MetricView(metric.key(), metric.label(), metric.unit(), metric.good().name(), value, previous,
                change, changePct, noise, provisionalDays, targetView, series, historyStartsOn, asOf);
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

    private FunnelStageView funnelStage(MetricCatalog.FunnelStage stage, ChannelBuild build) {
        Optional<MetricView> metric = build == null ? Optional.empty() : build.metric(stage.metric());
        if (metric.isEmpty() || (metric.get().value() == null && metric.get().previous() == null)) {
            return new FunnelStageView(stage.key(), stage.label(), null, null, null, stage.channel().name(), false,
                    stage.unavailableReason(), build == null ? null : build.window.to(), stage.channel().name(),
                    stage.conversionFrom());
        }
        MetricView m = metric.get();
        return new FunnelStageView(stage.key(), stage.label(), m.value(), m.previous(), m.change(),
                stage.channel().name(), true, null, build.window.to(), stage.channel().name(), stage.conversionFrom());
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
                    usable ? s.conversionFromKey() : null);
        }).toList();
    }

    // ---- dimension tables ---------------------------------------------------------------------------

    private List<DimensionTable> dimensions(Context ctx, MetricCatalog.Channel channel, ChannelBuild build) {
        Set<String> connectionIds = new HashSet<>();
        build.connections.forEach(c -> connectionIds.add(c.id()));
        List<DimensionTable> tables = new ArrayList<>();
        for (Dimension dimension : channel.dimensions()) {
            tables.add(dimensionTable(ctx, channel, dimension, connectionIds, build.window));
        }
        return tables;
    }

    private DimensionTable dimensionTable(Context ctx, MetricCatalog.Channel channel, Dimension dimension,
                                          Set<String> connectionIds, Window w) {
        List<String> sources = dimension.columns().stream().map(Column::source).filter(s -> s != null).distinct().toList();
        List<ChannelMetricDaily> rows = dailyRepository.findByProjectIdAndChannelAndMetricInAndDayBetween(
                ctx.projectId, channel.channel().name(), sources, ctx.historyStart.isBefore(w.from()) ? ctx.historyStart : w.from(),
                w.to().isBefore(ctx.today) ? ctx.today : w.to()).stream()
                .filter(r -> !r.getDimensionKey().isEmpty() && connectionIds.contains(r.getConnectionId()))
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

        String sortKey = dimension.columns().get(0).key();
        List<DimensionRowView> out = byKey.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Map<String, Double>> e) ->
                        e.getValue().getOrDefault(sortKey, Double.NEGATIVE_INFINITY)).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(DIMENSION_ROWS)
                .map(e -> toRow(channel.channel(), e.getKey(), e.getValue(), dimension))
                .toList();
        return new DimensionTable(dimension.key(), dimension.label(),
                dimension.columns().stream().map(c -> new ColumnView(c.key(), c.label(), c.unit())).toList(), out);
    }

    private DimensionRowView toRow(MetricChannel channel, String dimensionKey, Map<String, Double> values,
                                   Dimension dimension) {
        String key = dimensionKey;
        String label = dimensionKey;
        if (channel == MetricChannel.ADS) {
            // campaign:{id}:{name}; the name may itself contain colons, so split on the first two only
            String[] parts = dimensionKey.split(":", 3);
            if (parts.length == 3) {
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
        }
        List<Double> ordered = dimension.columns().stream().map(c -> values.get(c.key())).toList();
        return new DimensionRowView(key, label, ordered);
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
