package com.conductor.marketing.report;

import com.conductor.entity.ChannelMetricDaily;
import com.conductor.entity.WorkItem;
import com.conductor.integration.MetricChannel;
import com.conductor.marketing.links.TrackedLinkService;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.DimensionRowView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.DimensionTable;
import com.conductor.marketing.overview.MarketingOverviewQueryService.FunnelStageView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MetricView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Point;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Snapshot;
import com.conductor.marketing.overview.MarketingOverviewQueryService.SnapshotChannel;
import com.conductor.marketing.overview.MetricCatalog;
import com.conductor.marketing.overview.MetricCatalog.Definition;
import com.conductor.marketing.verification.VerificationLedgerService;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.WorkItemRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Assembles the marketing report: one compact payload with everything a written report needs. It adds no
 * numbers of its own beyond a few joins and differences; every value comes from
 * {@link MarketingOverviewQueryService#snapshot}, so the report and the overview can never disagree.
 *
 * <p><b>Organic App Store search</b> is the store's search first-time downloads minus Apple Ads first-time
 * downloads, and its search impressions minus Apple Ads impressions, summed over the days both sources have
 * settled (Apple counts a paid install as a search download too). Each is floored at 0 once, after summing, so a
 * day where paid exceeded the store's figure cannot hide the days where it did not. The row says which days it
 * covers ({@link Derivation}); a figure a source cannot yet give for those days is null, never 0.
 *
 * <p><b>Benchmarks</b> are judged only against a metric with the same definition ({@link BenchmarkComparability}):
 * when the metric a benchmark names counts something else, another metric of the same channel with the matching
 * definition is used instead and named in {@code comparedMetricRef}.
 *
 * <p><b>Channel quality</b> comes from the product-metrics contract v3 (see {@code docs/marketing-report.md}): cost
 * per outcome on keyword rows, payback by channel, the App Review exclusion, the EMAIL and SHARES rows, tracked-link
 * families and ASO keywords. Every figure is read, never defaulted: a key the feed did not send is null (the row or
 * section is omitted), and only a value the feed reported as 0 reads 0.
 */
@Service
public class MarketingReportService {

    static final String CHANGES_WORKFLOW = "MARKETING_CHANGES";
    static final String STATUS_PLANNED = "PLANNED";
    static final String STATUS_LIVE = "LIVE";
    static final String STATUS_REVIEWED = "REVIEWED";
    /** A keyword with fewer taps than this is too early to judge. */
    static final double JUDGEABLE_TAPS = 20;
    /** A rate resting on fewer observations than this is not judged against a benchmark. */
    static final long MIN_SAMPLE = 20;
    /** A benchmark checked longer ago than this should be refreshed. */
    static final int STALE_BENCHMARK_DAYS = 90;
    /** The period a count or currency benchmark is stated for. */
    static final int BENCHMARK_PERIOD_DAYS = 28;

    static final String NO_ADS_NOTE = "Apple Ads is not reporting, so any paid installs are counted here.";
    static final String WAITING_FOR_ADS_NOTE = "Waiting for Apple Ads first-time downloads for these days";
    static final String NO_SHARED_DAYS_NOTE = "No day in this window has both App Store search downloads and Apple "
            + "Ads first-time downloads settled yet.";
    static final String CONVERSION_SESSIONS_LABEL = "Sessions with an App Store tap";
    /** Most ASO keywords the report lists, best organic rank first. */
    static final int MAX_ASO_KEYWORDS = 50;
    static final String APP_REVIEW_EXCLUSION = "app_review_installs";
    static final String NO_ACTIVATIONS_NOTE = "no activations yet";
    static final String NO_RETAINED_NOTE = "no week-2 retained installs yet";
    static final String NO_TRIALS_NOTE = "no trials yet";
    static final String PAYBACK_BASIS = "Proceeds after Apple's commission for the people who first opened in the "
            + "window, counted over the contract's 12-month cohort horizon (a recent cohort stays low until it has "
            + "had the time), divided by the channel's spend in the same window.";
    static final String EMAIL_DEFINITION = "Lifecycle emails sent, clicks in them and free trials kept after one, as "
            + "the product backend counts them.";
    static final String SHARES_DEFINITION = "Share links created in the app, views of the shared pages and installs "
            + "that came from a share link, as the product backend counts them.";
    static final String LINK_DEFINITION = "First-time downloads Apple attributes to this family's campaign links "
            + "(App Store Connect campaign report). Apple leaves out a link under its privacy threshold, so a family "
            + "with no reported row has no figure, not 0.";
    static final String LINKS_NOT_MEASURABLE_NOTE = "App Store Connect is not connected, so these links' downloads "
            + "cannot be read";
    private static final String SEARCH_SOURCE = MetricCatalog.STORE_SOURCE_PREFIX + "APP_STORE_SEARCH";
    private static final String PRODUCT_CHANNEL = MetricChannel.PRODUCT.name();
    private static final String EMAIL_ROW = "email";
    private static final String SHARES_ROW = "shares";
    private static final String LINK_FAMILY_PREFIX = "link_family:";
    private static final String CHANNEL_DIMENSION = "channel:";
    private static final String ASO_DIMENSION = "aso:";
    /** The rolling windows the verification ledger serves; a report window outside them gets no summary. */
    private static final Set<Integer> LEDGER_ROLLING_DAYS = Set.of(7, 28, 90);
    /** Per acquisition row: the product feed's source names (lowercase letters and digits only) that are that channel. */
    private static final Map<String, Set<String>> PAYBACK_SOURCES = Map.of(
            "apple_ads", Set.of("applesearchads", "appleads", "asa"));
    private static final Map<String, List<String>> CHANNEL_ROW_METRICS = Map.of(
            EMAIL_ROW, List.of("email_sends", "email_clicks", "email_trials_saved"),
            SHARES_ROW, List.of("share_links_created", "share_views", "share_installs"));
    private static final List<String> ASO_METRICS = List.of("aso_rank", "aso_popularity");
    private static final String METRIC_NOT_IN_REPORT = "metric not in the report";
    private static final String DEFINITION_MISSING = "definition missing";
    /** Apple Ads reports whole UTC days everywhere except search terms, which only exist in the org's time zone. */
    private static final String ADS_DAY_ZONE = "UTC";
    private static final String ADS_SEARCH_TERM_ZONE = "ORTZ";

    /** The report metrics each acquisition row reads, so the definitions of exactly those are published with it. */
    private static final Map<String, List<String>> ACQUISITION_METRICS = Map.of(
            "app_store_search", List.of("STORE.impressions", "STORE.page_views", "STORE.downloads",
                    "ADS.new_downloads", "ADS.impressions"),
            "app_store_browse", List.of("STORE.impressions", "STORE.page_views", "STORE.downloads"),
            "web_referrer", List.of("WEB.visitors", "WEB.conversion_sessions", "WEB.conversion_clicks",
                    "STORE.downloads"),
            "store_other", List.of("STORE.downloads"),
            "apple_ads", List.of("ADS.impressions", "ADS.taps", "ADS.installs", "ADS.spend", "ADS.view_installs"),
            "search_engine", List.of("SEARCH.impressions", "SEARCH.clicks"),
            "social", List.of("SOCIAL.followers", "SOCIAL.website_clicks", "SOCIAL.likes"),
            EMAIL_ROW, List.of("PRODUCT.email_sends", "PRODUCT.email_clicks", "PRODUCT.email_trials_saved"),
            SHARES_ROW, List.of("PRODUCT.share_links_created", "PRODUCT.share_views", "PRODUCT.share_installs"));

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    // ---- result ------------------------------------------------------------------------------------

    public record Window(LocalDate from, LocalDate to) {}

    /**
     * {@code timeZone}: the zone that decides which day a row of this channel belongs to, null when the connector does
     * not state one. {@code searchTermTimeZone} (Apple Ads only): the zone of search-term days, which differs.
     */
    public record Source(String channel, String connector, String status, LocalDate dataCompleteThrough,
                         OffsetDateTime lastFetchedAt, String timeZone, String searchTermTimeZone) {}

    public record Metric(String channel, String key, String label, String unit, Double value, Double previous,
                         Double target, String status, Double missPct, Long sampleSize, String goodDirection,
                         Definition definition) {}

    /**
     * Where an organic figure comes from: both sides summed over the same {@code days} settled days
     * ({@code from} to {@code to}, not necessarily consecutive), before flooring at 0. The reach figures are null when a
     * source has no impressions for some of those days.
     */
    public record Derivation(LocalDate from, LocalDate to, int days, Double grossInstalls, Double paidInstalls,
                             Double grossReach, Double paidReach) {}

    /**
     * {@code viewThroughInstalls}: Apple Ads installs with no tap, never added to {@code installs} (null when not
     * reported). {@code likes}: the Social row's total account likes (null when no platform reports them).
     * {@code derivation}: set on the organic search row when it was computed. {@code installsLabel}: what the
     * {@code installs} figure counts when it is not installs (the Email row's "Trials saved"), else null.
     * {@code definitionLine}: one plain sentence saying what the row's figures are, set on the Email, Shares and
     * tracked-link rows, else null.
     */
    public record AcquisitionRow(String key, String label, String status, Double reach, String reachLabel,
                                 Double actions, String actionsLabel, Double installs, Double cost, boolean derived,
                                 String note, Double viewThroughInstalls, Double likes, Derivation derivation,
                                 String installsLabel, String definitionLine) {

        /** A row before the installs label and the definition line existed. */
        public AcquisitionRow(String key, String label, String status, Double reach, String reachLabel,
                              Double actions, String actionsLabel, Double installs, Double cost, boolean derived,
                              String note, Double viewThroughInstalls, Double likes, Derivation derivation) {
            this(key, label, status, reach, reachLabel, actions, actionsLabel, installs, cost, derived, note,
                    viewThroughInstalls, likes, derivation, null, null);
        }
    }

    /** How much of the window a figure covers: {@code daysCovered} of {@code daysInWindow}. */
    public record Coverage(int daysCovered, int daysInWindow) {}

    /** {@code coverage} and {@code note} say where an overridden stage value comes from (see {@link Store}). */
    public record FunnelStage(String key, String label, Double value, Double dropPct, boolean biggestDrop,
                              Coverage coverage, String note) {}

    public record Funnel(String basis, List<FunnelStage> stages) {}

    public record Day(LocalDate day, Double storeImpressions, Double adSpend, boolean provisional) {}

    public record ScorecardSource(String channel, String connector) {}

    public record ScorecardRow(String key, String label, String area, Double value, Double target,
                               String targetWindow, String status, Double missPct, String basis, Long sampleSize,
                               ScorecardSource source, Definition definition) {}

    /**
     * {@code installs} and {@code cpi}: Apple's tap installs (new downloads plus redownloads) and spend per tap
     * install, as on the campaign rows. {@code newDownloads}: first-time downloads from taps, a subset of installs.
     *
     * <p>{@code trials}, {@code activations} and {@code retainedW2} are the product feed's counts for the keyword
     * (null when the feed sent none: unknown, not zero). Each {@code costPer...} is the keyword's {@code spend} over
     * that count, null when the spend or the count is null or the count is 0; {@code qualityNote} says "No
     * activations yet" (and the like) for a count the feed reported as 0, else null.
     */
    public record Keyword(String keywordId, String text, String adGroup, String campaign, String status,
                          String matchType, Double bid, Double impressions, Double taps, Double tapRate,
                          Double installs, Double installRate, Double spend, Double cpi, Double trials,
                          Double activations, boolean judgeable, Double newDownloads, Double retainedW2,
                          Double costPerActivatedInstall, Double costPerRetainedW2Install, Double costPerTrial,
                          String qualityNote) {

        /** A keyword row before the retention and cost-per-outcome fields existed. */
        public Keyword(String keywordId, String text, String adGroup, String campaign, String status,
                       String matchType, Double bid, Double impressions, Double taps, Double tapRate,
                       Double installs, Double installRate, Double spend, Double cpi, Double trials,
                       Double activations, boolean judgeable, Double newDownloads) {
            this(keywordId, text, adGroup, campaign, status, matchType, bid, impressions, taps, tapRate, installs,
                    installRate, spend, cpi, trials, activations, judgeable, newDownloads, null, null, null, null,
                    null);
        }
    }

    public record Ad(String adGroup, String adId, String name, String creativeType, String productPageId,
                     String status, Double impressions, Double taps, Double installs, Double installRate,
                     Double spend, Double cpi) {}

    /**
     * {@code numerator}, {@code denominator} and {@code sourceType} are the benchmark's own definition (all null when it
     * has none). {@code comparedMetricRef} is the metric whose value is shown: the one the benchmark names, or a sibling
     * in its channel whose definition matches. {@code comparable} is false, with a {@code reason}, when no metric of the
     * channel has the benchmark's definition; {@code judged} is never true then.
     */
    public record Benchmark(String metricRef, String label, Double value, double benchmark, String rangeText,
                            String segment, String sourceName, String sourceUrl, LocalDate checkedOn, Double ratio,
                            String goodDirection, Long sampleSize, boolean judged, boolean stale, String numerator,
                            String denominator, String sourceType, String comparedMetricRef,
                            Definition metricDefinition, boolean comparable, String reason) {}

    /** One Apple Ads campaign: how much of its spend the keyword rows and the search-term rows account for. */
    public record AdsCoverage(String campaignId, String name, Double spend, Double keywordSpend,
                              Double searchTermSpend, Double keywordCoveragePct, int rows) {}

    public record ChangeAssignee(String userId, String name, String avatarUrl) {}

    public record Change(String id, String displayId, String title, String type, String status,
                         ChangeAssignee assignee, LocalDate dueOn, Map<String, Object> fields) {}

    public record Changes(List<Change> open, List<Change> dueForReview, List<Change> reviewedInWindow) {}

    /** The day a LIVE or REVIEWED change went live, for a chart to mark. */
    public record ChangeMarker(LocalDate day, String changeId, String displayId, String title, String status) {}

    /**
     * Both store download figures. {@code downloadsOfRecord}: Apple Sales Reports first-time downloads (never
     * privacy-thresholded), null when the connection has none. {@code analyticsDownloads}: App Store Connect Analytics
     * first-time downloads, which source-type splits stay on. {@code coverage}: the days Sales Reports were read.
     */
    public record Store(Double downloadsOfRecord, Double analyticsDownloads, Coverage coverage) {}

    /**
     * One acquisition channel's payback: {@code spend} and the cohort {@code proceedsNet12m} (the product feed's
     * {@code proceeds_net} at the channel's {@code source:} rows) with {@code ratio} = proceeds / spend. Proceeds and
     * ratio are null when the feed sent no proceeds for the channel's source, or the spend is 0; {@code note} then
     * says so.
     */
    public record PaybackRow(String channel, String label, Double spend, Double proceedsNet12m, Double ratio,
                             String note) {}

    /**
     * Payback per paid acquisition channel and in total. {@code total} sums the channels' spend and proceeds; its
     * ratio is null unless every channel has proceeds. {@code basis} states the 12-month cohort horizon.
     */
    public record Payback(List<PaybackRow> channels, PaybackRow total, String basis) {}

    /** What a figure leaves out: {@code value} is the count, {@code note} the feed's own wording or null. */
    public record Exclusion(String key, String label, Double value, String note) {}

    /**
     * One tracked App Store keyword: the latest organic {@code rank} (1 is first) and {@code popularity} at or before
     * the window's end. {@code previousRank} is the rank at the previous window's end and {@code rankChange} is
     * {@code rank - previousRank}, so a negative change is an improvement. Both are null when there is no earlier
     * reading, or no new reading since it.
     */
    public record AsoKeyword(String keyword, Double rank, Double popularity, Double rankChange, Double previousRank) {}

    /** The verification ledger's tally for the report window: how many metrics are in each state. */
    public record Verification(int unchecked, int mismatched, int staleReads, int both) {}

    public record Report(Window window, OffsetDateTime generatedAt, List<Source> sources, List<Metric> headline,
                         List<AcquisitionRow> acquisition, Funnel funnel, List<Day> daily,
                         List<ScorecardRow> scorecard, List<Keyword> keywords, List<Ad> ads,
                         List<Benchmark> benchmarks, Changes changes, List<AdsCoverage> adsCoverage,
                         List<ChangeMarker> changeMarkers, Map<String, Definition> definitions, Store store,
                         Payback payback, List<Exclusion> exclusions, List<AsoKeyword> asoKeywords,
                         Verification verification) {

        /** The report before the channel-quality sections (payback, exclusions, ASO, verification) existed. */
        public Report(Window window, OffsetDateTime generatedAt, List<Source> sources, List<Metric> headline,
                      List<AcquisitionRow> acquisition, Funnel funnel, List<Day> daily,
                      List<ScorecardRow> scorecard, List<Keyword> keywords, List<Ad> ads,
                      List<Benchmark> benchmarks, Changes changes, List<AdsCoverage> adsCoverage,
                      List<ChangeMarker> changeMarkers, Map<String, Definition> definitions, Store store) {
            this(window, generatedAt, sources, headline, acquisition, funnel, daily, scorecard, keywords, ads,
                    benchmarks, changes, adsCoverage, changeMarkers, definitions, store, null, List.of(), List.of(),
                    null);
        }

        /** The report before the coverage, marker, definition and store sections existed. */
        public Report(Window window, OffsetDateTime generatedAt, List<Source> sources, List<Metric> headline,
                      List<AcquisitionRow> acquisition, Funnel funnel, List<Day> daily,
                      List<ScorecardRow> scorecard, List<Keyword> keywords, List<Ad> ads,
                      List<Benchmark> benchmarks, Changes changes) {
            this(window, generatedAt, sources, headline, acquisition, funnel, daily, scorecard, keywords, ads,
                    benchmarks, changes, List.of(), List.of(), Map.of(), null);
        }
    }

    // ---- wiring ------------------------------------------------------------------------------------

    private final MarketingOverviewQueryService overviewService;
    private final MarketingBenchmarkRepository benchmarkRepository;
    private final WorkItemRepository workItemRepository;
    private final ChannelMetricDailyRepository dailyRepository;
    private final TrackedLinkService trackedLinkService;
    private final VerificationLedgerService ledgerService;
    private final ObjectMapper objectMapper;

    public MarketingReportService(MarketingOverviewQueryService overviewService,
                                  MarketingBenchmarkRepository benchmarkRepository,
                                  WorkItemRepository workItemRepository, ChannelMetricDailyRepository dailyRepository,
                                  TrackedLinkService trackedLinkService, VerificationLedgerService ledgerService,
                                  ObjectMapper objectMapper) {
        this.overviewService = overviewService;
        this.benchmarkRepository = benchmarkRepository;
        this.workItemRepository = workItemRepository;
        this.dailyRepository = dailyRepository;
        this.trackedLinkService = trackedLinkService;
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Report report(String projectId, String windowRaw) {
        return assemble(projectId, overviewService.snapshot(projectId, windowRaw), ledgerWindow(windowRaw),
                LocalDate.now(ZoneOffset.UTC), OffsetDateTime.now());
    }

    /** A blank window means the default for the ledger too; null is reserved for "the ledger serves no such window". */
    private static String ledgerWindow(String windowRaw) {
        return windowRaw == null ? "" : windowRaw;
    }

    /** Package-visible so a test can pin "today" and "now". */
    @Transactional(readOnly = true)
    Report report(String projectId, int days, LocalDate today, OffsetDateTime now) {
        return assemble(projectId, overviewService.snapshot(projectId, days, today, now),
                LEDGER_ROLLING_DAYS.contains(days) ? days + "d" : null, today, now);
    }

    /** Package-visible so a test can pin "today" and "now" for a window named as a request names it. */
    @Transactional(readOnly = true)
    Report report(String projectId, String windowRaw, LocalDate today, OffsetDateTime now) {
        return assemble(projectId, overviewService.snapshot(projectId, windowRaw, today, now),
                ledgerWindow(windowRaw), today, now);
    }

    /** {@code ledgerWindow}: the window as the verification ledger reads it, or null when it serves none like it. */
    private Report assemble(String projectId, Snapshot snap, String ledgerWindow, LocalDate today,
                            OffsetDateTime now) {
        Map<MetricChannel, SnapshotChannel> channels = snap.channels();
        LocalDate from = snap.overview().window().from();
        // null on a project with no data in any channel; nothing to clip then, and no value to judge
        LocalDate to = snap.overview().window().to();
        LocalDate end = to != null ? to : today;
        int windowDays = to == null ? BENCHMARK_PERIOD_DAYS : (int) (to.toEpochDay() - from.toEpochDay() + 1);

        Store store = store(snap, windowDays);
        List<Metric> headline = headline(channels);
        ProductReads productReads = productReads(projectId, channels.get(MetricChannel.PRODUCT), from, to, windowDays);
        List<AcquisitionRow> acquisition = new ArrayList<>(acquisition(snap, from, to));
        acquisition.addAll(emailAndSharesRows(channels, productReads));
        acquisition.addAll(linkFamilyRows(projectId, channels, from, to));
        List<ScorecardRow> scorecard = scorecard(channels);
        List<Benchmark> benchmarks = benchmarks(projectId, channels, today, windowDays);
        ChangeLog log = changeLog(projectId, from, end, today);
        Map<String, StageOverride> overrides = stageOverrides(channels, store);
        return new Report(new Window(from, to), now, sources(channels), headline, acquisition,
                funnel(snap, overrides), daily(channels, from, end), scorecard, keywords(channels), ads(channels),
                benchmarks, log.changes(), adsCoverage(channels), log.markers(),
                definitions(channels, headline, scorecard, acquisition, benchmarks, overrides, store), store,
                payback(channels, acquisition), exclusions(channels), productReads.asoKeywords(),
                verification(projectId, ledgerWindow));
    }

    // ---- sources and headline ----------------------------------------------------------------------

    private static boolean live(ChannelView view) {
        return !view.status().equals("NOT_CONNECTED") && !view.status().equals("COMING_SOON");
    }

    private static String connectors(ChannelView view) {
        String joined = view.connections().stream().map(MarketingOverviewQueryService.ConnectionView::connectorId)
                .distinct().collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    private static List<Source> sources(Map<MetricChannel, SnapshotChannel> channels) {
        List<Source> out = new ArrayList<>();
        channels.forEach((channel, c) -> {
            ChannelView v = c.view();
            if (v.status().equals("COMING_SOON")) {
                return;
            }
            boolean ads = channel == MetricChannel.ADS && live(v);
            out.add(new Source(v.channel(), connectors(v), v.status(), live(v) ? v.dataCompleteThrough() : null,
                    v.lastFetchedAt(), ads ? ADS_DAY_ZONE : null, ads ? ADS_SEARCH_TERM_ZONE : null));
        });
        return out;
    }

    private static MetricView metric(Map<MetricChannel, SnapshotChannel> channels, MetricChannel channel, String key) {
        SnapshotChannel c = channels.get(channel);
        return c == null ? null : c.view().metrics().stream().filter(m -> m.metric().equals(key)).findFirst().orElse(null);
    }

    private static Double value(Map<MetricChannel, SnapshotChannel> channels, MetricChannel channel, String key) {
        MetricView m = metric(channels, channel, key);
        return m == null ? null : m.value();
    }

    private static Metric toMetric(String channel, MetricView m) {
        return new Metric(channel, m.metric(), m.label(), m.unit().name(), m.value(), m.previous(),
                m.target() == null ? null : m.target().value(), m.status(), m.missPct(), m.sampleSize(),
                m.goodDirection(), m.definition());
    }

    private static List<Metric> headline(Map<MetricChannel, SnapshotChannel> channels) {
        List<Metric> out = new ArrayList<>();
        SnapshotChannel product = channels.get(MetricChannel.PRODUCT);
        if (product != null) {
            product.view().metrics().stream().filter(m -> m.headline() != null)
                    .sorted(Comparator.comparing(MetricView::headline))
                    .forEach(m -> out.add(toMetric("PRODUCT", m)));
        }
        for (String key : List.of("spend", "cost_per_install")) {
            MetricView m = metric(channels, MetricChannel.ADS, key);
            if (m != null) {
                out.add(toMetric("ADS", m));
            }
        }
        return out;
    }

    // ---- acquisition -------------------------------------------------------------------------------

    private static DimensionTable table(SnapshotChannel channel, String key) {
        return channel == null ? null
                : channel.dimensions().stream().filter(t -> t.key().equals(key)).findFirst().orElse(null);
    }

    /** column key -> value for one row of a table. */
    private static Map<String, Double> values(DimensionTable table, DimensionRowView row) {
        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i < table.columns().size() && i < row.values().size(); i++) {
            if (row.values().get(i) != null) {
                out.put(table.columns().get(i).key(), row.values().get(i));
            }
        }
        return out;
    }

    private static Double sum(Double a, Double b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Double.valueOf(a + b);
    }

    private static AcquisitionRow row(String key, String label, String status, Double reach, String reachLabel,
                                      Double actions, String actionsLabel, Double installs, Double cost,
                                      boolean derived, String note) {
        return new AcquisitionRow(key, label, status, reach, reachLabel, actions, actionsLabel, installs, cost, derived,
                note, null, null, null);
    }

    private static AcquisitionRow notConnected(String key, String label, ChannelView view) {
        return row(key, label, view.status(), null, null, null, null, null, null, false, null);
    }

    private static List<AcquisitionRow> acquisition(Snapshot snap, LocalDate from, LocalDate to) {
        Map<MetricChannel, SnapshotChannel> channels = snap.channels();
        List<AcquisitionRow> out = new ArrayList<>();
        out.addAll(storeRows(snap, from, to));
        out.addAll(adsRows(channels));
        out.addAll(searchEngineRows(channels));
        out.addAll(socialRows(channels));
        return out;
    }

    private static boolean connected(SnapshotChannel channel) {
        return channel != null && live(channel.view());
    }

    private static List<AcquisitionRow> storeRows(Snapshot snap, LocalDate from, LocalDate to) {
        Map<MetricChannel, SnapshotChannel> channels = snap.channels();
        SnapshotChannel store = channels.get(MetricChannel.STORE);
        if (store != null && !live(store.view())) {
            return List.of(notConnected("app_store", "App Store", store.view()));
        }
        if (store == null) {
            return List.of();
        }
        String status = store.view().status();
        Map<String, Map<String, Double>> bySource = new LinkedHashMap<>();
        DimensionTable sources = table(store, "sources");
        if (sources != null) {
            sources.rows().forEach(r -> bySource.put(r.key(), values(sources, r)));
        }
        List<AcquisitionRow> out = new ArrayList<>();
        out.add(organicSearchRow(snap, status, bySource.getOrDefault("APP_STORE_SEARCH", Map.of()), from, to));

        Map<String, Double> browse = bySource.getOrDefault("APP_STORE_BROWSE", Map.of());
        Map<String, Double> referrer = bySource.getOrDefault("APP_REFERRER", Map.of());
        out.add(row("app_store_browse", "App Store browse and app referrers", status,
                sum(browse.get("impressions"), referrer.get("impressions")), "Impressions",
                sum(browse.get("page_views"), referrer.get("page_views")), "Page views",
                sum(browse.get("downloads"), referrer.get("downloads")), null, false, null));
        out.add(webReferrerRow(channels, status, bySource.getOrDefault("WEB_REFERRER", Map.of())));

        Double other = null;
        for (Map.Entry<String, Map<String, Double>> e : bySource.entrySet()) {
            if (!List.of("APP_STORE_SEARCH", "APP_STORE_BROWSE", "APP_REFERRER", "WEB_REFERRER").contains(e.getKey())) {
                other = sum(other, e.getValue().get("downloads"));
            }
        }
        if (other != null && other > 0) {
            out.add(row("store_other", "Other store sources", status, null, null, null, null, other, null, false, null));
        }
        return out;
    }

    private static AcquisitionRow webReferrerRow(Map<MetricChannel, SnapshotChannel> channels, String status,
                                                 Map<String, Double> webRef) {
        boolean webLive = connected(channels.get(MetricChannel.WEB));
        Double visitors = webLive ? value(channels, MetricChannel.WEB, "visitors") : null;
        Double tapSessions = webLive ? value(channels, MetricChannel.WEB, "conversion_sessions") : null;
        Double clicks = webLive ? value(channels, MetricChannel.WEB, "conversion_clicks") : null;
        Double actions = tapSessions != null ? tapSessions : clicks != null ? clicks : webRef.get("page_views");
        String actionsLabel = tapSessions != null ? CONVERSION_SESSIONS_LABEL
                : clicks != null ? "Conversion clicks" : "Page views";
        return row("web_referrer", "Web referrer", status,
                visitors != null ? visitors : webRef.get("impressions"),
                visitors != null ? "Web visitors" : "Impressions", actions, actionsLabel, webRef.get("downloads"),
                null, false,
                visitors != null ? "Reach and actions are the website's own; installs are App Store downloads "
                        + "that came from a web referrer." : null);
    }

    /** What the organic search figures turned out to be; {@code derivation} only when they were computed. */
    private record OrganicSearch(Double installs, Double reach, boolean derived, String note, Derivation derivation) {
        static OrganicSearch gross(Map<String, Double> search, String note) {
            return new OrganicSearch(search.get("downloads"), search.get("impressions"), false, note, null);
        }

        static OrganicSearch unknown(String note) {
            return new OrganicSearch(null, null, false, note, null);
        }
    }

    private static AcquisitionRow organicSearchRow(Snapshot snap, String status, Map<String, Double> search,
                                                   LocalDate from, LocalDate to) {
        boolean adsLive = connected(snap.channels().get(MetricChannel.ADS));
        OrganicSearch organic = organicSearch(snap, adsLive, search, from, to);
        return new AcquisitionRow("app_store_search", "App Store search (organic)", status, organic.reach(),
                "Impressions (organic)", search.get("page_views"), "Page views", organic.installs(), null,
                organic.derived(), organic.note(), null, null, organic.derivation());
    }

    /**
     * Organic search = what the store credits to search, minus what Apple Ads bought, over the days where both
     * sources have a settled value for first-time downloads. Summing over one shared set of days is what keeps the two
     * sides comparable; each side is floored at 0 only after summing.
     */
    private static OrganicSearch organicSearch(Snapshot snap, boolean adsLive, Map<String, Double> search,
                                               LocalDate from, LocalDate to) {
        if (!adsLive || to == null) {
            return OrganicSearch.gross(search, NO_ADS_NOTE);
        }
        Map<LocalDate, Double> paidDownloads = settled(snap, MetricChannel.ADS, "new_downloads", "", from, to);
        if (paidDownloads.isEmpty()) {
            // ads are reporting days but none of them carries first-time downloads: data from before that field
            return adsReportsDays(snap, from, to) ? OrganicSearch.unknown(WAITING_FOR_ADS_NOTE)
                    : OrganicSearch.gross(search, NO_ADS_NOTE);
        }
        Map<LocalDate, Double> searchDownloads = settled(snap, MetricChannel.STORE, "downloads", SEARCH_SOURCE, from, to);
        TreeSet<LocalDate> days = new TreeSet<>(searchDownloads.keySet());
        days.retainAll(paidDownloads.keySet());
        if (days.isEmpty()) {
            return OrganicSearch.unknown(NO_SHARED_DAYS_NOTE);
        }
        Double grossInstalls = total(searchDownloads, days);
        Double paidInstalls = total(paidDownloads, days);
        Double grossReach = coveredTotal(settled(snap, MetricChannel.STORE, "impressions", SEARCH_SOURCE, from, to), days);
        Double paidReach = coveredTotal(settled(snap, MetricChannel.ADS, "impressions", "", from, to), days);
        boolean reachKnown = grossReach != null && paidReach != null;
        Derivation derivation = new Derivation(days.first(), days.last(), days.size(), grossInstalls, paidInstalls,
                reachKnown ? grossReach : null, reachKnown ? paidReach : null);
        String note = "App Store search first-time downloads minus Apple Ads first-time downloads, and search "
                + "impressions minus Apple Ads impressions, over the " + days.size() + " days both have settled ("
                + days.first() + "–" + days.last() + "), floored at 0."
                + (reachKnown ? "" : " Impressions are missing for some of these days, so organic impressions are "
                + "unknown.");
        return new OrganicSearch(Math.max(0, grossInstalls - paidInstalls),
                reachKnown ? Math.max(0, grossReach - paidReach) : null, true, note, derivation);
    }

    private static boolean adsReportsDays(Snapshot snap, LocalDate from, LocalDate to) {
        return List.of("spend", "impressions", "installs").stream()
                .anyMatch(metric -> !settled(snap, MetricChannel.ADS, metric, "", from, to).isEmpty());
    }

    /** A stored metric's settled values on the days of the report window only. */
    private static Map<LocalDate, Double> settled(Snapshot snap, MetricChannel channel, String metric, String dimension,
                                                  LocalDate from, LocalDate to) {
        Map<LocalDate, Double> out = new TreeMap<>();
        snap.dailyValues(channel, metric, dimension).forEach((day, v) -> {
            if (!day.isBefore(from) && !day.isAfter(to)) {
                out.put(day, v);
            }
        });
        return out;
    }

    private static Double total(Map<LocalDate, Double> byDay, Set<LocalDate> days) {
        double sum = 0;
        for (LocalDate day : days) {
            sum += byDay.get(day);
        }
        return sum;
    }

    /** The sum over {@code days}, or null when any of those days has no value: a partial sum would mix day ranges. */
    private static Double coveredTotal(Map<LocalDate, Double> byDay, Set<LocalDate> days) {
        return byDay.keySet().containsAll(days) ? total(byDay, days) : null;
    }

    private static List<AcquisitionRow> adsRows(Map<MetricChannel, SnapshotChannel> channels) {
        SnapshotChannel ads = channels.get(MetricChannel.ADS);
        if (ads == null) {
            return List.of();
        }
        if (!live(ads.view())) {
            return List.of(notConnected("apple_ads", "Apple Ads", ads.view()));
        }
        return List.of(new AcquisitionRow("apple_ads", "Apple Ads", ads.view().status(),
                value(channels, MetricChannel.ADS, "impressions"), "Impressions",
                value(channels, MetricChannel.ADS, "taps"), "Taps", value(channels, MetricChannel.ADS, "installs"),
                value(channels, MetricChannel.ADS, "spend"), false, null,
                value(channels, MetricChannel.ADS, "view_installs"), null, null));
    }

    private static List<AcquisitionRow> searchEngineRows(Map<MetricChannel, SnapshotChannel> channels) {
        SnapshotChannel search = channels.get(MetricChannel.SEARCH);
        if (search == null) {
            return List.of();
        }
        if (!live(search.view())) {
            return List.of(notConnected("search_engine", "Search engine", search.view()));
        }
        return List.of(row("search_engine", "Search engine", search.view().status(),
                value(channels, MetricChannel.SEARCH, "impressions"), "Impressions",
                value(channels, MetricChannel.SEARCH, "clicks"), "Clicks", null, null, false,
                "Search visits do not report installs."));
    }

    private static List<AcquisitionRow> socialRows(Map<MetricChannel, SnapshotChannel> channels) {
        SnapshotChannel social = channels.get(MetricChannel.SOCIAL);
        if (social == null) {
            return List.of();
        }
        if (!live(social.view())) {
            return List.of(notConnected("social", "Social", social.view()));
        }
        boolean hasClicks = metric(channels, MetricChannel.SOCIAL, "website_clicks") != null;
        return List.of(new AcquisitionRow("social", "Social", social.view().status(),
                value(channels, MetricChannel.SOCIAL, "followers"), "Followers",
                hasClicks ? value(channels, MetricChannel.SOCIAL, "website_clicks") : null,
                hasClicks ? "Website clicks" : null, null, null, false, null, null,
                value(channels, MetricChannel.SOCIAL, "likes"), null));
    }

    // ---- store downloads of record -----------------------------------------------------------------

    /**
     * Both store download figures and, when Sales Reports have a value, how many of the window's days they were read on.
     * Coverage is unknown (null), not 0, when no day count was stored.
     */
    private static Store store(Snapshot snap, int windowDays) {
        Map<MetricChannel, SnapshotChannel> channels = snap.channels();
        SnapshotChannel store = channels.get(MetricChannel.STORE);
        if (!connected(store)) {
            return null;
        }
        Double ofRecord = value(channels, MetricChannel.STORE, "downloads_of_record");
        Coverage coverage = ofRecord == null ? null : salesReportCoverage(snap, store.view().dataCompleteThrough(),
                windowDays);
        return new Store(ofRecord, value(channels, MetricChannel.STORE, "downloads"), coverage);
    }

    private static Coverage salesReportCoverage(Snapshot snap, LocalDate through, int windowDays) {
        if (through == null) {
            return null;
        }
        Map<LocalDate, Double> days = settled(snap, MetricChannel.STORE, "sales_report_days", "",
                through.minusDays(windowDays - 1L), through);
        return days.isEmpty() ? null : new Coverage((int) Math.round(days.values().stream()
                .mapToDouble(Double::doubleValue).sum()), windowDays);
    }

    // ---- funnel ------------------------------------------------------------------------------------

    /** A funnel stage whose value the report reads from a better source than the overview's metric. */
    private record StageOverride(Double value, String label, Coverage coverage, String note, String metricRef) {}

    private static Map<String, StageOverride> stageOverrides(Map<MetricChannel, SnapshotChannel> channels, Store store) {
        Map<String, StageOverride> out = new HashMap<>();
        if (store != null && store.downloadsOfRecord() != null) {
            Coverage c = store.coverage();
            out.put("store_downloads", new StageOverride(store.downloadsOfRecord(), null, c,
                    "Apple Sales Reports first-time downloads, " + (c == null ? "day coverage unknown"
                            : c.daysCovered() + " of " + c.daysInWindow() + " days covered"),
                    "STORE.downloads_of_record"));
        }
        Double tapSessions = connected(channels.get(MetricChannel.WEB))
                ? value(channels, MetricChannel.WEB, "conversion_sessions") : null;
        if (tapSessions != null) {
            Double sessions = value(channels, MetricChannel.WEB, "sessions");
            out.put("web_conversion_clicks", new StageOverride(tapSessions, CONVERSION_SESSIONS_LABEL, null,
                    "Sessions with the conversion event" + (sessions == null ? "" : ": " + count(tapSessions) + " of "
                            + count(sessions) + " sessions"), "WEB.conversion_sessions"));
        }
        return out;
    }

    private static Funnel funnel(Snapshot snap, Map<String, StageOverride> overrides) {
        List<FunnelStageView> all = snap.overview().funnel().stream()
                .filter(s -> s.available() && s.value() != null).toList();
        List<FunnelStageView> product = all.stream().filter(s -> s.key().startsWith("product_")).toList();
        List<FunnelStage> stages = new ArrayList<>();
        String basis;
        if (!product.isEmpty()) {
            basis = product.stream().anyMatch(s -> "COHORT".equals(s.basis())) ? "COHORT" : "EVENT";
            Double previous = null;
            for (FunnelStageView s : product) {
                stages.add(new FunnelStage(s.key().substring("product_".length()), s.label(), s.value(),
                        drop(previous, s.value()), false, null, null));
                previous = s.value();
            }
        } else {
            basis = snap.overview().funnelBasis();
            Map<String, Double> byKey = all.stream().collect(Collectors.toMap(FunnelStageView::key,
                    s -> overrides.containsKey(s.key()) ? overrides.get(s.key()).value() : s.value(), (a, b) -> a));
            for (FunnelStageView s : all) {
                StageOverride o = overrides.get(s.key());
                Double value = o != null ? o.value() : s.value();
                stages.add(new FunnelStage(s.key(), o != null && o.label() != null ? o.label() : s.label(), value,
                        s.conversionFromKey() == null ? null : drop(byKey.get(s.conversionFromKey()), value), false,
                        o == null ? null : o.coverage(), o == null ? null : o.note()));
            }
        }
        int biggest = -1;
        for (int i = 0; i < stages.size(); i++) {
            Double d = stages.get(i).dropPct();
            if (d != null && d > 0 && (biggest < 0 || d > stages.get(biggest).dropPct())) {
                biggest = i;
            }
        }
        if (biggest >= 0) {
            FunnelStage s = stages.get(biggest);
            stages.set(biggest, new FunnelStage(s.key(), s.label(), s.value(), s.dropPct(), true, s.coverage(), s.note()));
        }
        return new Funnel(basis, stages);
    }

    private static Double drop(Double previous, Double value) {
        return previous == null || previous <= 0 || value == null ? null : (previous - value) / previous;
    }

    // ---- daily -------------------------------------------------------------------------------------

    /** Each source's series reaches back before the window (history for the change detector); the report shows only the window. */
    private static List<Day> daily(Map<MetricChannel, SnapshotChannel> channels, LocalDate from, LocalDate to) {
        Map<LocalDate, Double[]> byDay = new TreeMap<>();
        Map<LocalDate, Boolean> provisional = new HashMap<>();
        collect(metric(channels, MetricChannel.STORE, "impressions"), 0, byDay, provisional);
        collect(metric(channels, MetricChannel.ADS, "spend"), 1, byDay, provisional);
        List<Day> out = new ArrayList<>();
        byDay.forEach((day, v) -> {
            if (!day.isBefore(from) && !day.isAfter(to)) {
                out.add(new Day(day, v[0], v[1], provisional.getOrDefault(day, false)));
            }
        });
        return out;
    }

    private static void collect(MetricView metric, int slot, Map<LocalDate, Double[]> byDay,
                                Map<LocalDate, Boolean> provisional) {
        if (metric == null) {
            return;
        }
        for (Point p : metric.series()) {
            byDay.computeIfAbsent(p.day(), d -> new Double[2])[slot] = p.value();
            if (p.provisional()) {
                provisional.put(p.day(), true);
            }
        }
    }

    // ---- scorecard ---------------------------------------------------------------------------------

    private static List<ScorecardRow> scorecard(Map<MetricChannel, SnapshotChannel> channels) {
        List<ScorecardRow> out = new ArrayList<>();
        for (SnapshotChannel c : channels.values()) {
            for (MetricView m : c.view().metrics()) {
                if (m.target() == null && m.area() == null) {
                    continue;
                }
                out.add(new ScorecardRow(m.metric(), m.label(), m.area(), m.value(),
                        m.target() == null ? null : m.target().value(),
                        m.target() == null ? null : m.target().window(), m.status(), m.missPct(),
                        basis(c, m), m.sampleSize(), new ScorecardSource(c.view().channel(), connectors(c.view())),
                        m.definition()));
            }
        }
        return out;
    }

    /** The metric's own description, else "2 of 30" for a ratio of two counts. */
    private static String basis(SnapshotChannel channel, MetricView m) {
        if (m.description() != null && !m.description().isBlank()) {
            return m.description();
        }
        MetricCatalog.Metric def = channel.catalog().metric(m.metric()).orElse(null);
        if (def == null || def.agg() != MetricCatalog.Aggregation.DERIVED || def.numerator() == null) {
            return null;
        }
        Double n = channel.view().metrics().stream().filter(x -> x.metric().equals(def.numerator()))
                .map(MetricView::value).filter(Objects::nonNull).findFirst().orElse(null);
        Double d = channel.view().metrics().stream().filter(x -> x.metric().equals(def.denominator()))
                .map(MetricView::value).filter(Objects::nonNull).findFirst().orElse(null);
        return n == null || d == null ? null : count(n) + " of " + count(d);
    }

    private static String count(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    // ---- keywords and ads --------------------------------------------------------------------------

    private static String str(Map<String, Object> state, String key) {
        Object v = state == null ? null : state.get(key);
        return v == null ? null : v.toString();
    }

    private static Double num(Map<String, Object> state, String key) {
        Object v = state == null ? null : state.get(key);
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static boolean productMetric(SnapshotChannel product, String key) {
        return product != null && product.view().metrics().stream().anyMatch(m -> m.metric().equals(key));
    }

    private static List<Keyword> keywords(Map<MetricChannel, SnapshotChannel> channels) {
        SnapshotChannel ads = channels.get(MetricChannel.ADS);
        DimensionTable table = table(ads, "keywords");
        if (table == null) {
            return List.of();
        }
        // in-app outcomes by keyword id: PRODUCT rows are keyword:{id}:{text}, the table key is "{id}:{text}"
        Map<String, Map<String, Double>> outcomes = new HashMap<>();
        DimensionTable product = table(channels.get(MetricChannel.PRODUCT), "keywords");
        if (product != null) {
            for (DimensionRowView r : product.rows()) {
                int colon = r.key().indexOf(':');
                outcomes.put(colon < 0 ? r.key() : r.key().substring(0, colon), values(product, r));
            }
        }
        // Outcomes are read from these product metric keys by default; a workspace whose PRODUCT channel defines
        // neither gets no outcome fields rather than nulls that look like "measured, unknown".
        SnapshotChannel productChannel = channels.get(MetricChannel.PRODUCT);
        boolean hasTrial = productMetric(productChannel, "trial_started");
        boolean hasActivated = productMetric(productChannel, "activated");
        boolean hasRetained = productMetric(productChannel, "retained_w2");
        List<Keyword> out = new ArrayList<>();
        for (DimensionRowView r : table.rows().stream().limit(MarketingOverviewQueryService.REPORT_KEYWORDS).toList()) {
            Map<String, Double> v = values(table, r);
            Map<String, Object> state = r.state();
            Map<String, Double> outcome = outcomes.get(r.key());
            Double taps = v.get("taps");
            Double spend = v.get("spend");
            Double trials = outcome == null || !hasTrial ? null : outcome.get("trial_started");
            Double activations = outcome == null || !hasActivated ? null : outcome.get("activated");
            Double retained = outcome == null || !hasRetained ? null : outcome.get("retained_w2");
            out.add(new Keyword(r.key(), r.label(), str(state, "adGroupName"), str(state, "campaignName"),
                    str(state, "status"), str(state, "matchType"), num(state, "bidAmount"), v.get("impressions"),
                    taps, v.get("tap_through_rate"), v.get("installs"), v.get("install_rate"), spend,
                    v.get("cost_per_install"), trials, activations, taps != null && taps >= JUDGEABLE_TAPS,
                    v.get("new_downloads"), retained, costPer(spend, activations), costPer(spend, retained),
                    costPer(spend, trials), qualityNote(activations, retained, trials)));
        }
        return out;
    }

    /** Spend per outcome: null unless both are known and the outcome count is above 0 (no division by nothing). */
    private static Double costPer(Double spend, Double count) {
        return spend == null || count == null || count <= 0 ? null : spend / count;
    }

    /** Names each outcome the feed reported as 0, so a null cost reads as "none yet" rather than "unknown"; else null. */
    private static String qualityNote(Double activations, Double retained, Double trials) {
        List<String> parts = new ArrayList<>();
        if (activations != null && activations == 0) {
            parts.add(NO_ACTIVATIONS_NOTE);
        }
        if (retained != null && retained == 0) {
            parts.add(NO_RETAINED_NOTE);
        }
        if (trials != null && trials == 0) {
            parts.add(NO_TRIALS_NOTE);
        }
        if (parts.isEmpty()) {
            return null;
        }
        String note = String.join("; ", parts);
        return Character.toUpperCase(note.charAt(0)) + note.substring(1);
    }

    private static List<Ad> ads(Map<MetricChannel, SnapshotChannel> channels) {
        DimensionTable table = table(channels.get(MetricChannel.ADS), "ads");
        if (table == null) {
            return List.of();
        }
        List<Ad> out = new ArrayList<>();
        for (DimensionRowView r : table.rows()) {
            Map<String, Double> v = values(table, r);
            Map<String, Object> state = r.state();
            // ad:{adGroupId}:{adId}:{name} reaches the table as key={adGroupId}:{adId}, label={name}
            int colon = r.key().indexOf(':');
            String adId = colon < 0 ? r.key() : r.key().substring(colon + 1);
            String name = r.label();
            out.add(new Ad(str(state, "adGroupName"), adId, name.isBlank() ? adId : name, str(state, "creativeType"),
                    str(state, "productPageId"), str(state, "status"), v.get("impressions"), v.get("taps"),
                    v.get("installs"), v.get("install_rate"), v.get("spend"), v.get("cost_per_install")));
        }
        return out;
    }

    // ---- product feed: email, shares and ASO keywords ----------------------------------------------

    /**
     * What the report's one extra query read from the product feed: per metric, the window sum of each
     * {@code channel:<name>} row, and the ASO keywords built from the {@code aso:<keyword>} rows. Both are empty when
     * the feed declares none of those metrics, and then no query is made.
     */
    private record ProductReads(Map<String, Map<String, Double>> channelSums, List<AsoKeyword> asoKeywords) {
        static final ProductReads NONE = new ProductReads(Map.of(), List.of());

        Double channelSum(String metric, String channel) {
            return channelSums.getOrDefault(metric, Map.of()).get(channel);
        }
    }

    /**
     * One query, whatever the number of keywords or channels: the feed's {@code channel:} and {@code aso:} rows for the
     * metrics it declares. The snapshot holds the feed's day totals only, so these dimension rows are read here. The
     * range reaches back one window (at least 28 days) before the window's start so a rank has a previous reading.
     */
    private ProductReads productReads(String projectId, SnapshotChannel product, LocalDate from, LocalDate to,
                                      int windowDays) {
        if (!connected(product) || to == null) {
            return ProductReads.NONE;
        }
        Set<String> declared = product.catalog().metrics().stream().map(MetricCatalog.Metric::key)
                .collect(Collectors.toSet());
        List<String> wanted = new ArrayList<>();
        CHANNEL_ROW_METRICS.values().forEach(wanted::addAll);
        wanted.addAll(ASO_METRICS);
        wanted.removeIf(key -> !declared.contains(key));
        if (wanted.isEmpty()) {
            return ProductReads.NONE;
        }
        Set<String> connectionIds = product.view().connections().stream()
                .map(MarketingOverviewQueryService.ConnectionView::id).collect(Collectors.toSet());
        LocalDate lookbackStart = from.minusDays(Math.max(windowDays, BENCHMARK_PERIOD_DAYS));
        List<ChannelMetricDaily> rows = dailyRepository.findByProjectIdAndChannelAndMetricInAndDayBetween(projectId,
                PRODUCT_CHANNEL, wanted, lookbackStart, to).stream()
                .filter(r -> connectionIds.contains(r.getConnectionId())).toList();
        return new ProductReads(channelSums(rows, from, to), asoKeywords(rows, from, to));
    }

    /** metric key -> channel name (lowercase) -> sum over the window of that metric's {@code channel:<name>} rows. */
    private static Map<String, Map<String, Double>> channelSums(List<ChannelMetricDaily> rows, LocalDate from,
                                                                LocalDate to) {
        Map<String, Map<String, Double>> out = new HashMap<>();
        for (ChannelMetricDaily r : rows) {
            String dimension = r.getDimensionKey();
            if (!dimension.startsWith(CHANNEL_DIMENSION) || r.getDay().isBefore(from) || r.getDay().isAfter(to)) {
                continue;
            }
            String channel = dimension.substring(CHANNEL_DIMENSION.length()).trim().toLowerCase(Locale.ROOT);
            out.computeIfAbsent(r.getMetric(), k -> new HashMap<>()).merge(channel, r.getValue().doubleValue(),
                    Double::sum);
        }
        return out;
    }

    /**
     * Rank and popularity are levels: each keyword's value is its latest reading at or before the window's end, and
     * the previous rank is its latest reading at or before the previous window's end (the day before the window). A
     * change needs a reading after the previous one: when the latest reading <em>is</em> the previous one, nothing
     * has been read since, and the change is null, not 0. Best rank first, unranked last, at most
     * {@link #MAX_ASO_KEYWORDS}.
     */
    private static List<AsoKeyword> asoKeywords(List<ChannelMetricDaily> rows, LocalDate from, LocalDate to) {
        Map<String, TreeMap<LocalDate, Double>> ranks = new HashMap<>();
        Map<String, TreeMap<LocalDate, Double>> popularity = new HashMap<>();
        for (ChannelMetricDaily r : rows) {
            String dimension = r.getDimensionKey();
            if (!dimension.startsWith(ASO_DIMENSION) || r.getDay().isAfter(to)) {
                continue;
            }
            Map<String, TreeMap<LocalDate, Double>> target = r.getMetric().equals("aso_rank") ? ranks
                    : r.getMetric().equals("aso_popularity") ? popularity : null;
            if (target != null) {
                target.computeIfAbsent(dimension.substring(ASO_DIMENSION.length()), k -> new TreeMap<>())
                        .put(r.getDay(), r.getValue().doubleValue());
            }
        }
        Set<String> keywords = new TreeSet<>(ranks.keySet());
        keywords.addAll(popularity.keySet());
        List<AsoKeyword> out = new ArrayList<>();
        for (String keyword : keywords) {
            Map.Entry<LocalDate, Double> rank = latest(ranks.get(keyword), to);
            Map.Entry<LocalDate, Double> previous = latest(ranks.get(keyword), from.minusDays(1));
            Map.Entry<LocalDate, Double> reach = latest(popularity.get(keyword), to);
            boolean read = rank != null && previous != null && rank.getKey().isAfter(previous.getKey());
            out.add(new AsoKeyword(keyword, rank == null ? null : rank.getValue(),
                    reach == null ? null : reach.getValue(), read ? rank.getValue() - previous.getValue() : null,
                    read ? previous.getValue() : null));
        }
        out.sort(Comparator.comparing(AsoKeyword::rank, Comparator.nullsLast(Comparator.<Double>naturalOrder()))
                .thenComparing(AsoKeyword::popularity, Comparator.nullsLast(Comparator.<Double>reverseOrder()))
                .thenComparing(AsoKeyword::keyword));
        return out.size() > MAX_ASO_KEYWORDS ? new ArrayList<>(out.subList(0, MAX_ASO_KEYWORDS)) : out;
    }

    private static Map.Entry<LocalDate, Double> latest(TreeMap<LocalDate, Double> series, LocalDate day) {
        return series == null ? null : series.floorEntry(day);
    }

    /** A product figure: the feed's day total for the window, else the sum of its {@code channel:<name>} rows. */
    private static Double productFigure(Map<MetricChannel, SnapshotChannel> channels, ProductReads reads,
                                        String metric, String channelName) {
        Double total = value(channels, MetricChannel.PRODUCT, metric);
        return total != null ? total : reads.channelSum(metric, channelName);
    }

    private static String labelled(Double figure, String label) {
        return figure == null ? null : label;
    }

    /**
     * The Email and Shares rows. A row is omitted when none of its metrics has a value; a metric without one leaves its
     * figure and its label null.
     */
    private static List<AcquisitionRow> emailAndSharesRows(Map<MetricChannel, SnapshotChannel> channels,
                                                           ProductReads reads) {
        SnapshotChannel product = channels.get(MetricChannel.PRODUCT);
        if (!connected(product)) {
            return List.of();
        }
        String status = product.view().status();
        List<AcquisitionRow> out = new ArrayList<>();
        Double sends = productFigure(channels, reads, "email_sends", EMAIL_ROW);
        Double clicks = productFigure(channels, reads, "email_clicks", EMAIL_ROW);
        Double saved = productFigure(channels, reads, "email_trials_saved", EMAIL_ROW);
        if (sends != null || clicks != null || saved != null) {
            out.add(new AcquisitionRow(EMAIL_ROW, "Email", status, sends, labelled(sends, "Emails sent"), clicks,
                    labelled(clicks, "Clicks"), saved, null, false, null, null, null, null,
                    labelled(saved, "Trials saved"), emailDefinition(channels)));
        }
        Double links = productFigure(channels, reads, "share_links_created", SHARES_ROW);
        Double views = productFigure(channels, reads, "share_views", SHARES_ROW);
        Double installs = productFigure(channels, reads, "share_installs", SHARES_ROW);
        if (links != null || views != null || installs != null) {
            out.add(new AcquisitionRow(SHARES_ROW, "Shares", status, links, labelled(links, "Links shared"), views,
                    labelled(views, "Views"), installs, null, false, null, null, null, null,
                    labelled(installs, "Installs"), SHARES_DEFINITION));
        }
        return out;
    }

    /** What the Email row counts; the feed states its own rule for "trials saved" in the metric's description. */
    private static String emailDefinition(Map<MetricChannel, SnapshotChannel> channels) {
        MetricView saved = metric(channels, MetricChannel.PRODUCT, "email_trials_saved");
        String rule = saved == null || saved.description() == null || saved.description().isBlank() ? null
                : saved.description().strip();
        return EMAIL_DEFINITION + (rule == null ? "" : " Trials saved: " + rule);
    }

    // ---- tracked-link families ---------------------------------------------------------------------

    /**
     * One row per tracked-link family, from {@link TrackedLinkService#performance} over the report window. Downloads are
     * null (never 0) with the "below Apple's reporting threshold" note when no token of the family has a row Apple
     * reported; a project with no tracked links has no rows. Without a connected App Store the downloads cannot be
     * read, and the row says so instead of blaming Apple's threshold.
     */
    private List<AcquisitionRow> linkFamilyRows(String projectId, Map<MetricChannel, SnapshotChannel> channels,
                                                LocalDate from, LocalDate to) {
        if (to == null) {
            return List.of();
        }
        SnapshotChannel store = channels.get(MetricChannel.STORE);
        boolean measurable = connected(store);
        String status = store == null ? "NOT_CONNECTED" : store.view().status();
        List<AcquisitionRow> out = new ArrayList<>();
        for (TrackedLinkService.FamilyPerformance family : trackedLinkService.performance(projectId, from, to)
                .families()) {
            Double views = family.views() == null ? null : family.views().doubleValue();
            Double downloads = !measurable || family.downloads() == null ? null : family.downloads().doubleValue();
            out.add(new AcquisitionRow(LINK_FAMILY_PREFIX + family.family(),
                    family.family() + " links (" + family.kind() + ")", status, views, labelled(views, "Post views"),
                    null, null, downloads, null, false, measurable ? family.note() : LINKS_NOT_MEASURABLE_NOTE, null,
                    null, null, "Downloads", LINK_DEFINITION));
        }
        return out;
    }

    // ---- payback, exclusions and verification ------------------------------------------------------

    /**
     * Payback for each paid acquisition row that has spend and a known source in the product feed (today Apple Ads, at
     * {@code source:apple_search_ads}): the feed's {@code proceeds_net} over the window's cohorts divided by the row's
     * spend. Null when the feed declares no {@code proceeds_net} or no row has spend. Proceeds are the contract's
     * 12-month cohort figure, so a recent window reads low until its cohorts mature.
     */
    private static Payback payback(Map<MetricChannel, SnapshotChannel> channels, List<AcquisitionRow> acquisition) {
        SnapshotChannel product = channels.get(MetricChannel.PRODUCT);
        if (!connected(product) || !productMetric(product, "proceeds_net")) {
            return null;
        }
        DimensionTable sources = table(product, "sources");
        List<PaybackRow> rows = new ArrayList<>();
        for (AcquisitionRow a : acquisition) {
            Set<String> names = PAYBACK_SOURCES.get(a.key());
            if (names == null || a.cost() == null) {
                continue;
            }
            Double proceeds = sourceProceeds(sources, names);
            rows.add(paybackRow(a.key(), a.label(), a.cost(), proceeds, proceeds == null
                    ? "The product feed sent no proceeds for a source matching " + a.label() : null));
        }
        if (rows.isEmpty()) {
            return null;
        }
        double spend = rows.stream().mapToDouble(PaybackRow::spend).sum();
        Double proceeds = rows.stream().map(PaybackRow::proceedsNet12m).filter(Objects::nonNull)
                .reduce(Double::sum).orElse(null);
        boolean complete = rows.stream().allMatch(r -> r.proceedsNet12m() != null);
        PaybackRow total = new PaybackRow("total", "All paid channels", spend, proceeds,
                complete ? ratio(spend, proceeds) : null,
                complete ? null : "At least one channel has no proceeds, so there is no total ratio");
        return new Payback(rows, total, PAYBACK_BASIS);
    }

    private static PaybackRow paybackRow(String channel, String label, double spend, Double proceeds, String note) {
        return new PaybackRow(channel, label, spend, proceeds, ratio(spend, proceeds), note);
    }

    /** Proceeds per unit of spend; null without proceeds or with no spend to divide by. */
    private static Double ratio(double spend, Double proceeds) {
        return proceeds == null || spend <= 0 ? null : proceeds / spend;
    }

    /** The window's {@code proceeds_net} at the {@code source:} rows whose name is one of {@code names}; null when none. */
    private static Double sourceProceeds(DimensionTable sources, Set<String> names) {
        if (sources == null) {
            return null;
        }
        Double proceeds = null;
        for (DimensionRowView r : sources.rows()) {
            if (names.contains(r.key().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))) {
                proceeds = sum(proceeds, values(sources, r).get("proceeds_net"));
            }
        }
        return proceeds;
    }

    /** Likely App Review installs the feed left out of its other numbers; omitted unless the feed reports a count. */
    private static List<Exclusion> exclusions(Map<MetricChannel, SnapshotChannel> channels) {
        if (!connected(channels.get(MetricChannel.PRODUCT))) {
            return List.of();
        }
        MetricView excluded = metric(channels, MetricChannel.PRODUCT, "app_review_installs_excluded");
        if (excluded == null || excluded.value() == null) {
            return List.of();
        }
        String note = excluded.definition() == null ? null : excluded.definition().exclusions();
        return List.of(new Exclusion(APP_REVIEW_EXCLUSION, "Likely App Review installs", excluded.value(), note));
    }

    /**
     * The ledger's tally for the same window, so a reader sees how much of the report is verified. Null when the
     * ledger has no metric to check (nothing connected) or serves no window like this one.
     */
    private Verification verification(String projectId, String ledgerWindow) {
        if (ledgerWindow == null) {
            return null;
        }
        VerificationLedgerService.Ledger ledger = ledgerService.ledger(projectId, ledgerWindow);
        if (ledger.rows().isEmpty()) {
            return null;
        }
        VerificationLedgerService.Summary s = ledger.summary();
        return new Verification(s.unchecked(), s.mismatched(), s.staleReads(), s.both());
    }

    // ---- ads coverage ------------------------------------------------------------------------------

    /**
     * Per campaign (largest spend first), how much of its spend the keyword rows and the search-term rows account for.
     * A keyword row belongs to the campaign named in its state; a search-term row's key starts with its campaign id. A
     * campaign none of them reach shows 0 and {@code rows} 0, which is the gap to look at, not an unknown.
     */
    private static List<AdsCoverage> adsCoverage(Map<MetricChannel, SnapshotChannel> channels) {
        SnapshotChannel ads = channels.get(MetricChannel.ADS);
        DimensionTable campaigns = connected(ads) ? table(ads, "campaigns") : null;
        if (campaigns == null) {
            return List.of();
        }
        Map<String, double[]> keywordSpend = new HashMap<>();
        DimensionTable keywords = table(ads, "keywords");
        if (keywords != null) {
            for (DimensionRowView r : keywords.rows()) {
                tally(keywordSpend, str(r.state(), "campaignId"), values(keywords, r).get("spend"));
            }
        }
        Map<String, double[]> termSpend = new HashMap<>();
        DimensionTable terms = table(ads, "searchterms");
        if (terms != null) {
            for (DimensionRowView r : terms.rows()) {
                int colon = r.key().indexOf(':');
                tally(termSpend, colon < 0 ? null : r.key().substring(0, colon), values(terms, r).get("spend"));
            }
        }
        List<AdsCoverage> out = new ArrayList<>();
        for (DimensionRowView r : campaigns.rows()) {
            Double spend = values(campaigns, r).get("spend");
            double[] kw = keywordSpend.getOrDefault(r.key(), new double[2]);
            double[] st = termSpend.getOrDefault(r.key(), new double[2]);
            out.add(new AdsCoverage(r.key(), r.label(), spend, kw[0], st[0],
                    spend == null || spend <= 0 ? null : kw[0] / spend, (int) (kw[1] + st[1])));
        }
        return out;
    }

    /** Adds one row to a campaign's {spend, row count}; a row with no campaign belongs to none. */
    private static void tally(Map<String, double[]> byCampaign, String campaignId, Double spend) {
        if (campaignId == null) {
            return;
        }
        double[] t = byCampaign.computeIfAbsent(campaignId, id -> new double[2]);
        t[0] += spend == null ? 0 : spend;
        t[1]++;
    }

    // ---- benchmarks --------------------------------------------------------------------------------

    /**
     * A count or currency total (SUM) benchmark is stated per {@link #BENCHMARK_PERIOD_DAYS} days and prorated
     * to the report window, so the 7d and 28d verdicts agree. A ratio, rate, average or level is compared as stated.
     */
    private List<Benchmark> benchmarks(String projectId, Map<MetricChannel, SnapshotChannel> channels, LocalDate today,
                                       int windowDays) {
        List<Benchmark> out = new ArrayList<>();
        for (MarketingBenchmark b : benchmarkRepository.findByProjectIdOrderByMetricRefAsc(projectId)) {
            out.add(benchmark(b, compare(b, channels), today, windowDays));
        }
        return out;
    }

    /** The metric a benchmark is judged against, and whether its definition matches the benchmark's. */
    private record Compared(String ref, MetricView metric, BenchmarkComparability.Result result) {}

    private static boolean hasDefinition(MarketingBenchmark b) {
        return BenchmarkComparability.measure(b.getNumerator()) != null;
    }

    private static BenchmarkComparability.Result compareDefinitions(MarketingBenchmark b, MetricView m) {
        Definition d = m == null ? null : m.definition();
        return BenchmarkComparability.compare(b.getNumerator(), b.getDenominator(), b.getSourceType(),
                d == null ? null : d.numerator(), d == null ? null : d.denominator(),
                d == null ? null : d.sourceType());
    }

    private static Compared compare(MarketingBenchmark b, Map<MetricChannel, SnapshotChannel> channels) {
        String ref = b.getMetricRef();
        int dot = ref.indexOf('.');
        MetricChannel channel = null;
        MetricView named = null;
        if (dot > 0) {
            try {
                channel = MetricChannel.valueOf(ref.substring(0, dot));
                named = metric(channels, channel, ref.substring(dot + 1));
            } catch (IllegalArgumentException ignored) {
                // an unknown channel in a stored reference just has no value
            }
        }
        if (!hasDefinition(b)) {
            return new Compared(ref, named, new BenchmarkComparability.Result(false, DEFINITION_MISSING));
        }
        if (named == null) {
            return new Compared(ref, null, new BenchmarkComparability.Result(false, METRIC_NOT_IN_REPORT));
        }
        BenchmarkComparability.Result own = compareDefinitions(b, named);
        if (own.comparable()) {
            return new Compared(ref, named, own);
        }
        MetricView sibling = siblingWithDefinition(b, channels.get(channel), named);
        return sibling == null ? new Compared(ref, named, own)
                : new Compared(channel.name() + "." + sibling.metric(), sibling,
                        new BenchmarkComparability.Result(true, null));
    }

    /** Another visible metric of the channel whose definition matches the benchmark's; one with a value wins. */
    private static MetricView siblingWithDefinition(MarketingBenchmark b, SnapshotChannel channel, MetricView named) {
        List<MetricView> matches = channel.view().metrics().stream()
                .filter(m -> m != named && compareDefinitions(b, m).comparable()).toList();
        return matches.stream().filter(m -> m.value() != null).findFirst()
                .orElse(matches.isEmpty() ? null : matches.get(0));
    }

    private static Benchmark benchmark(MarketingBenchmark b, Compared compared, LocalDate today, int windowDays) {
        MetricView m = compared.metric();
        Double value = m == null ? null : m.value();
        double benchmark = b.getValue().doubleValue();
        if (m != null && "SUM".equals(m.aggregation())
                && (m.unit() == MetricCatalog.Unit.COUNT || m.unit() == MetricCatalog.Unit.CURRENCY)) {
            benchmark = benchmark * windowDays / BENCHMARK_PERIOD_DAYS;
        }
        boolean rate = m != null && (m.unit() == MetricCatalog.Unit.PERCENT || "DERIVED".equals(m.aggregation()));
        boolean comparable = compared.result().comparable();
        boolean judged = comparable && value != null && !(rate && m.sampleSize() != null && m.sampleSize() < MIN_SAMPLE);
        return new Benchmark(b.getMetricRef(), b.getLabel() != null ? b.getLabel() : m == null ? null : m.label(),
                value, benchmark, b.getRangeText(), b.getSegment(), b.getSourceName(), b.getSourceUrl(),
                b.getCheckedOn(), value == null || benchmark == 0 ? null : value / benchmark,
                m == null ? null : m.goodDirection(), m == null ? null : m.sampleSize(), judged,
                b.getCheckedOn().isBefore(today.minusDays(STALE_BENCHMARK_DAYS)), b.getNumerator(),
                b.getDenominator(), b.getSourceType(), compared.ref(), m == null ? null : m.definition(),
                comparable, compared.result().reason());
    }

    // ---- definitions -------------------------------------------------------------------------------

    /**
     * One definition per metric the report mentions, by {@code CHANNEL.key}: the headline and scorecard metrics, the
     * metrics each acquisition row reads, the metric each benchmark was compared with and any funnel or store figure
     * read from a metric of its own. A metric that is not in the report (for example a connector that does not report it)
     * is left out, so a key here always has a figure somewhere in the payload.
     */
    private static Map<String, Definition> definitions(Map<MetricChannel, SnapshotChannel> channels,
                                                       List<Metric> headline, List<ScorecardRow> scorecard,
                                                       List<AcquisitionRow> acquisition, List<Benchmark> benchmarks,
                                                       Map<String, StageOverride> overrides, Store store) {
        Set<String> refs = new LinkedHashSet<>();
        headline.forEach(m -> refs.add(m.channel() + "." + m.key()));
        scorecard.forEach(r -> refs.add(r.source().channel() + "." + r.key()));
        acquisition.forEach(a -> refs.addAll(ACQUISITION_METRICS.getOrDefault(a.key(), List.of())));
        benchmarks.forEach(b -> refs.add(b.comparedMetricRef()));
        overrides.values().forEach(o -> refs.add(o.metricRef()));
        if (store != null) {
            refs.add("STORE.downloads");
        }
        Map<String, Definition> out = new LinkedHashMap<>();
        for (String ref : refs) {
            int dot = ref.indexOf('.');
            MetricView m = null;
            try {
                m = dot < 0 ? null : metric(channels, MetricChannel.valueOf(ref.substring(0, dot)), ref.substring(dot + 1));
            } catch (IllegalArgumentException ignored) {
                // an unknown channel has no metric
            }
            if (m != null && m.definition() != null) {
                out.put(ref, m.definition());
            }
        }
        return out;
    }

    // ---- changes -----------------------------------------------------------------------------------

    private record ChangeLog(Changes changes, List<ChangeMarker> markers) {}

    /**
     * One query for the change log. "Reviewed in the window" is read from {@code completed_at} (when the change entered
     * REVIEWED), falling back to {@code updated_at} for a change that has no completion time. A change is
     * <em>due for review</em> when it is PLANNED or LIVE, its review date is before today and it has no verdict yet.
     * Markers come from the same rows, so a REVIEWED change reviewed before the window has none.
     */
    private ChangeLog changeLog(String projectId, LocalDate from, LocalDate to, LocalDate today) {
        List<WorkItem> items = workItemRepository.findChangeLog(projectId, CHANGES_WORKFLOW,
                List.of(STATUS_PLANNED, STATUS_LIVE), STATUS_REVIEWED, from.atStartOfDay().atOffset(ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        List<Change> open = new ArrayList<>();
        List<Change> due = new ArrayList<>();
        List<Change> reviewed = new ArrayList<>();
        List<ChangeMarker> markers = new ArrayList<>();
        for (WorkItem item : items) {
            Change change = toChange(item);
            switch (item.getCurrentStatus()) {
                case STATUS_PLANNED -> {
                    open.add(change);
                    addIfDue(due, item, change, today);
                }
                case STATUS_LIVE -> {
                    open.add(change);
                    addIfDue(due, item, change, today);
                    addMarker(markers, item, to);
                }
                case STATUS_REVIEWED -> {
                    reviewed.add(change);
                    addMarker(markers, item, to);
                }
                default -> { }
            }
        }
        markers.sort(Comparator.comparing(ChangeMarker::day).thenComparing(ChangeMarker::displayId));
        return new ChangeLog(new Changes(open, due, reviewed), markers);
    }

    private static void addIfDue(List<Change> due, WorkItem item, Change change, LocalDate today) {
        if (item.getDueOn() != null && item.getDueOn().isBefore(today) && !hasVerdict(item)) {
            due.add(change);
        }
    }

    private static boolean hasVerdict(WorkItem item) {
        JsonNode verdict = item.getFields() == null ? null : item.getFields().get("verdict");
        return verdict != null && verdict.isTextual() && !verdict.asText().isBlank();
    }

    private static void addMarker(List<ChangeMarker> markers, WorkItem item, LocalDate windowEnd) {
        LocalDate day = liveSince(item);
        if (day != null && !day.isAfter(windowEnd)) {
            markers.add(new ChangeMarker(day, item.getId(), displayId(item), item.getTitle(), item.getCurrentStatus()));
        }
    }

    /** The {@code liveSince} field's date (a leading yyyy-MM-dd), or null when it is missing or not a date. */
    private static LocalDate liveSince(WorkItem item) {
        JsonNode field = item.getFields() == null ? null : item.getFields().get("liveSince");
        if (field == null || !field.isTextual() || field.asText().trim().length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(field.asText().trim().substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String displayId(WorkItem item) {
        return item.getProject().getKey() + "-" + item.getSequenceNumber();
    }

    private Change toChange(WorkItem item) {
        ChangeAssignee assignee = item.getAssignee() == null ? null
                : new ChangeAssignee(item.getAssignee().getId(), item.getAssignee().getName(),
                        item.getAssignee().getAvatarUrl());
        Map<String, Object> fields = item.getFields() == null || item.getFields().isEmpty() ? null
                : objectMapper.convertValue(item.getFields(), MAP);
        return new Change(item.getId(), displayId(item), item.getTitle(), item.getType(), item.getCurrentStatus(),
                assignee, item.getDueOn(), fields);
    }
}
