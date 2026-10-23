package com.conductor.marketing.report;

import com.conductor.entity.WorkItem;
import com.conductor.integration.MetricChannel;
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
import com.conductor.repository.WorkItemRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Assembles the marketing report: one compact payload with everything a written report needs. It adds no
 * numbers of its own beyond a few joins and differences; every value comes from
 * {@link MarketingOverviewQueryService#snapshot}, so the report and the overview can never disagree.
 *
 * <p><b>Organic App Store search</b> is the store's search downloads minus Apple Ads installs (Apple counts
 * a paid install as a search download too), floored at 0 and flagged {@code derived}. Both are totals over each
 * channel's own window, so the row says so when the two windows end on different days.
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

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    // ---- result ------------------------------------------------------------------------------------

    public record Window(LocalDate from, LocalDate to) {}

    public record Source(String channel, String connector, String status, LocalDate dataCompleteThrough,
                         OffsetDateTime lastFetchedAt) {}

    public record Metric(String channel, String key, String label, String unit, Double value, Double previous,
                         Double target, String status, Double missPct, Long sampleSize, String goodDirection) {}

    public record AcquisitionRow(String key, String label, String status, Double reach, String reachLabel,
                                 Double actions, String actionsLabel, Double installs, Double cost, boolean derived,
                                 String note) {}

    public record FunnelStage(String key, String label, Double value, Double dropPct, boolean biggestDrop) {}

    public record Funnel(String basis, List<FunnelStage> stages) {}

    public record Day(LocalDate day, Double storeImpressions, Double adSpend, boolean provisional) {}

    public record ScorecardSource(String channel, String connector) {}

    public record ScorecardRow(String key, String label, String area, Double value, Double target,
                               String targetWindow, String status, Double missPct, String basis, Long sampleSize,
                               ScorecardSource source) {}

    public record Keyword(String keywordId, String text, String adGroup, String campaign, String status,
                          String matchType, Double bid, Double impressions, Double taps, Double tapRate,
                          Double installs, Double installRate, Double spend, Double cpi, Double trials,
                          Double activations, boolean judgeable) {}

    public record Ad(String adGroup, String adId, String name, String creativeType, String productPageId,
                     String status, Double impressions, Double taps, Double installs, Double installRate,
                     Double spend, Double cpi) {}

    public record Benchmark(String metricRef, String label, Double value, double benchmark, String rangeText,
                            String segment, String sourceName, String sourceUrl, LocalDate checkedOn, Double ratio,
                            String goodDirection, Long sampleSize, boolean judged, boolean stale) {}

    public record ChangeAssignee(String userId, String name, String avatarUrl) {}

    public record Change(String id, String displayId, String title, String type, String status,
                         ChangeAssignee assignee, LocalDate dueOn, Map<String, Object> fields) {}

    public record Changes(List<Change> open, List<Change> dueForReview, List<Change> reviewedInWindow) {}

    public record Report(Window window, OffsetDateTime generatedAt, List<Source> sources, List<Metric> headline,
                         List<AcquisitionRow> acquisition, Funnel funnel, List<Day> daily,
                         List<ScorecardRow> scorecard, List<Keyword> keywords, List<Ad> ads,
                         List<Benchmark> benchmarks, Changes changes) {}

    // ---- wiring ------------------------------------------------------------------------------------

    private final MarketingOverviewQueryService overviewService;
    private final MarketingBenchmarkRepository benchmarkRepository;
    private final WorkItemRepository workItemRepository;
    private final ObjectMapper objectMapper;

    public MarketingReportService(MarketingOverviewQueryService overviewService,
                                  MarketingBenchmarkRepository benchmarkRepository,
                                  WorkItemRepository workItemRepository, ObjectMapper objectMapper) {
        this.overviewService = overviewService;
        this.benchmarkRepository = benchmarkRepository;
        this.workItemRepository = workItemRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Report report(String projectId, String windowRaw) {
        return assemble(projectId, overviewService.snapshot(projectId, windowRaw), LocalDate.now(ZoneOffset.UTC),
                OffsetDateTime.now());
    }

    /** Package-visible so a test can pin "today" and "now". */
    @Transactional(readOnly = true)
    Report report(String projectId, int days, LocalDate today, OffsetDateTime now) {
        return assemble(projectId, overviewService.snapshot(projectId, days, today, now), today, now);
    }

    private Report assemble(String projectId, Snapshot snap, LocalDate today, OffsetDateTime now) {
        Map<MetricChannel, SnapshotChannel> channels = snap.channels();
        LocalDate from = snap.overview().window().from();
        LocalDate to = snap.overview().window().to();
        return new Report(new Window(from, to), now, sources(channels), headline(channels),
                acquisition(channels), funnel(snap), daily(channels), scorecard(channels), keywords(channels),
                ads(channels), benchmarks(projectId, channels, today), changes(projectId, from, today));
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
        for (SnapshotChannel c : channels.values()) {
            ChannelView v = c.view();
            if (v.status().equals("COMING_SOON")) {
                continue;
            }
            out.add(new Source(v.channel(), connectors(v), v.status(), live(v) ? v.dataCompleteThrough() : null,
                    v.lastFetchedAt()));
        }
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
                m.goodDirection());
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

    /** Sum of the ADS keyword rows' installs (first-time downloads), or null when there are none. */
    private static Double keywordFirstTimeDownloads(SnapshotChannel ads) {
        DimensionTable table = table(ads, "keywords");
        if (table == null) {
            return null;
        }
        Double total = null;
        for (DimensionRowView r : table.rows()) {
            total = sum(total, values(table, r).get("installs"));
        }
        return total;
    }

    private static Double sum(Double a, Double b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Double.valueOf(a + b);
    }

    private static AcquisitionRow notConnected(String key, String label, ChannelView view) {
        return new AcquisitionRow(key, label, view.status(), null, null, null, null, null, null, false, null);
    }

    private static List<AcquisitionRow> acquisition(Map<MetricChannel, SnapshotChannel> channels) {
        List<AcquisitionRow> out = new ArrayList<>();
        SnapshotChannel store = channels.get(MetricChannel.STORE);
        SnapshotChannel ads = channels.get(MetricChannel.ADS);
        SnapshotChannel web = channels.get(MetricChannel.WEB);
        boolean adsLive = ads != null && live(ads.view());
        Double adsInstalls = adsLive ? value(channels, MetricChannel.ADS, "installs") : null;

        if (store != null && !live(store.view())) {
            out.add(notConnected("app_store", "App Store", store.view()));
        } else if (store != null) {
            String status = store.view().status();
            Map<String, Map<String, Double>> bySource = new LinkedHashMap<>();
            DimensionTable sources = table(store, "sources");
            if (sources != null) {
                sources.rows().forEach(r -> bySource.put(r.key(), values(sources, r)));
            }
            Map<String, Double> search = bySource.getOrDefault("APP_STORE_SEARCH", Map.of());
            Double searchDownloads = search.get("downloads");
            // Apple Ads "installs" include redownloads, but store search downloads are first-time only, so subtract
            // the first-time downloads: the per-keyword install rows are Apple's tapNewDownloads. Keyword rows miss
            // other placements and untargeted matches, hence a lower-bound estimate.
            Double adsFirstTime = adsLive ? keywordFirstTimeDownloads(ads) : null;
            Double adsSubtract = adsFirstTime != null ? adsFirstTime : adsInstalls;
            boolean subtract = adsSubtract != null && searchDownloads != null;
            Double organic = searchDownloads == null ? null
                    : subtract ? Math.max(0, searchDownloads - adsSubtract) : searchDownloads;
            String note = !subtract
                    ? "Apple Ads is not reporting, so any paid installs are counted here."
                    : adsFirstTime != null
                    ? "Store search downloads minus Apple Ads first-time downloads (summed from keyword rows), floored "
                            + "at 0. A lower-bound estimate: it cannot exclude non-search ad placements, so organic "
                            + "may be lower." + windowNote(store, ads)
                    : "Store search downloads minus Apple Ads installs, floored at 0: Apple counts an ad install as a "
                            + "search download too. Ad installs include redownloads, so organic may be understated."
                            + windowNote(store, ads);
            out.add(new AcquisitionRow("app_store_search", "App Store search (organic)", status,
                    search.get("impressions"), "Impressions", search.get("page_views"), "Page views",
                    organic, null, subtract, note));

            Map<String, Double> browse = bySource.getOrDefault("APP_STORE_BROWSE", Map.of());
            Map<String, Double> referrer = bySource.getOrDefault("APP_REFERRER", Map.of());
            out.add(new AcquisitionRow("app_store_browse", "App Store browse and app referrers", status,
                    sum(browse.get("impressions"), referrer.get("impressions")), "Impressions",
                    sum(browse.get("page_views"), referrer.get("page_views")), "Page views",
                    sum(browse.get("downloads"), referrer.get("downloads")), null, false, null));

            Map<String, Double> webRef = bySource.getOrDefault("WEB_REFERRER", Map.of());
            boolean webLive = web != null && live(web.view());
            Double visitors = webLive ? value(channels, MetricChannel.WEB, "visitors") : null;
            Double clicks = webLive ? value(channels, MetricChannel.WEB, "conversion_clicks") : null;
            out.add(new AcquisitionRow("web_referrer", "Web referrer", status,
                    visitors != null ? visitors : webRef.get("impressions"),
                    visitors != null ? "Web visitors" : "Impressions",
                    clicks != null ? clicks : webRef.get("page_views"),
                    clicks != null ? "Conversion clicks" : "Page views",
                    webRef.get("downloads"), null, false,
                    visitors != null ? "Reach and actions are the website's own; installs are App Store downloads "
                            + "that came from a web referrer." : null));

            Double other = null;
            for (Map.Entry<String, Map<String, Double>> e : bySource.entrySet()) {
                if (!List.of("APP_STORE_SEARCH", "APP_STORE_BROWSE", "APP_REFERRER", "WEB_REFERRER").contains(e.getKey())) {
                    other = sum(other, e.getValue().get("downloads"));
                }
            }
            if (other != null && other > 0) {
                out.add(new AcquisitionRow("store_other", "Other store sources", status, null, null, null, null,
                        other, null, false, null));
            }
        }

        if (ads != null && !adsLive) {
            out.add(notConnected("apple_ads", "Apple Ads", ads.view()));
        } else if (ads != null) {
            out.add(new AcquisitionRow("apple_ads", "Apple Ads", ads.view().status(),
                    value(channels, MetricChannel.ADS, "impressions"), "Impressions",
                    value(channels, MetricChannel.ADS, "taps"), "Taps", adsInstalls,
                    value(channels, MetricChannel.ADS, "spend"), false, null));
        }

        SnapshotChannel search = channels.get(MetricChannel.SEARCH);
        if (search != null && !live(search.view())) {
            out.add(notConnected("search_engine", "Search engine", search.view()));
        } else if (search != null) {
            out.add(new AcquisitionRow("search_engine", "Search engine", search.view().status(),
                    value(channels, MetricChannel.SEARCH, "impressions"), "Impressions",
                    value(channels, MetricChannel.SEARCH, "clicks"), "Clicks", null, null, false,
                    "Search visits do not report installs."));
        }

        SnapshotChannel social = channels.get(MetricChannel.SOCIAL);
        if (social != null && !live(social.view())) {
            out.add(notConnected("social", "Social", social.view()));
        } else if (social != null) {
            boolean hasClicks = metric(channels, MetricChannel.SOCIAL, "website_clicks") != null;
            out.add(new AcquisitionRow("social", "Social", social.view().status(),
                    value(channels, MetricChannel.SOCIAL, "followers"), "Followers",
                    hasClicks ? value(channels, MetricChannel.SOCIAL, "website_clicks") : null,
                    hasClicks ? "Website clicks" : null, null, null, false, null));
        }
        return out;
    }

    private static String windowNote(SnapshotChannel store, SnapshotChannel ads) {
        if (store == null || ads == null || Objects.equals(store.view().dataCompleteThrough(), ads.view().dataCompleteThrough())) {
            return "";
        }
        return " The two sources end on different days (store " + store.view().dataCompleteThrough() + ", ads "
                + ads.view().dataCompleteThrough() + ").";
    }

    // ---- funnel ------------------------------------------------------------------------------------

    private static Funnel funnel(Snapshot snap) {
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
                        drop(previous, s.value()), false));
                previous = s.value();
            }
        } else {
            basis = snap.overview().funnelBasis();
            Map<String, Double> byKey = all.stream().collect(Collectors.toMap(FunnelStageView::key,
                    FunnelStageView::value, (a, b) -> a));
            for (FunnelStageView s : all) {
                stages.add(new FunnelStage(s.key(), s.label(), s.value(),
                        s.conversionFromKey() == null ? null : drop(byKey.get(s.conversionFromKey()), s.value()), false));
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
            stages.set(biggest, new FunnelStage(s.key(), s.label(), s.value(), s.dropPct(), true));
        }
        return new Funnel(basis, stages);
    }

    private static Double drop(Double previous, Double value) {
        return previous == null || previous <= 0 || value == null ? null : (previous - value) / previous;
    }

    // ---- daily -------------------------------------------------------------------------------------

    private static List<Day> daily(Map<MetricChannel, SnapshotChannel> channels) {
        Map<LocalDate, Double[]> byDay = new TreeMap<>();
        Map<LocalDate, Boolean> provisional = new HashMap<>();
        collect(metric(channels, MetricChannel.STORE, "impressions"), 0, byDay, provisional);
        collect(metric(channels, MetricChannel.ADS, "spend"), 1, byDay, provisional);
        List<Day> out = new ArrayList<>();
        byDay.forEach((day, v) -> out.add(new Day(day, v[0], v[1], provisional.getOrDefault(day, false))));
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
                        basis(c, m), m.sampleSize(), new ScorecardSource(c.view().channel(), connectors(c.view()))));
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
        List<Keyword> out = new ArrayList<>();
        for (DimensionRowView r : table.rows().stream().limit(MarketingOverviewQueryService.REPORT_KEYWORDS).toList()) {
            Map<String, Double> v = values(table, r);
            Map<String, Object> state = r.state();
            Map<String, Double> outcome = outcomes.get(r.key());
            Double taps = v.get("taps");
            out.add(new Keyword(r.key(), r.label(), str(state, "adGroupName"), str(state, "campaignName"),
                    str(state, "status"), str(state, "matchType"), num(state, "bidAmount"), v.get("impressions"),
                    taps, v.get("tap_through_rate"), v.get("installs"), v.get("install_rate"), v.get("spend"),
                    v.get("cost_per_install"), outcome == null || !hasTrial ? null : outcome.get("trial_started"),
                    outcome == null || !hasActivated ? null : outcome.get("activated"), taps != null && taps >= JUDGEABLE_TAPS));
        }
        return out;
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

    // ---- benchmarks --------------------------------------------------------------------------------

    private List<Benchmark> benchmarks(String projectId, Map<MetricChannel, SnapshotChannel> channels, LocalDate today) {
        List<Benchmark> out = new ArrayList<>();
        for (MarketingBenchmark b : benchmarkRepository.findByProjectIdOrderByMetricRefAsc(projectId)) {
            int dot = b.getMetricRef().indexOf('.');
            MetricView m = null;
            if (dot > 0) {
                try {
                    m = metric(channels, MetricChannel.valueOf(b.getMetricRef().substring(0, dot)),
                            b.getMetricRef().substring(dot + 1));
                } catch (IllegalArgumentException ignored) {
                    // an unknown channel in a stored reference just has no value
                }
            }
            Double value = m == null ? null : m.value();
            double benchmark = b.getValue().doubleValue();
            boolean rate = m != null && (m.unit() == MetricCatalog.Unit.PERCENT || "DERIVED".equals(m.aggregation()));
            boolean judged = value != null && !(rate && m.sampleSize() != null && m.sampleSize() < MIN_SAMPLE);
            out.add(new Benchmark(b.getMetricRef(), b.getLabel() != null ? b.getLabel() : m == null ? null : m.label(),
                    value, benchmark, b.getRangeText(), b.getSegment(), b.getSourceName(), b.getSourceUrl(),
                    b.getCheckedOn(), value == null || benchmark == 0 ? null : value / benchmark,
                    m == null ? null : m.goodDirection(), m == null ? null : m.sampleSize(), judged,
                    b.getCheckedOn().isBefore(today.minusDays(STALE_BENCHMARK_DAYS))));
        }
        return out;
    }

    // ---- changes -----------------------------------------------------------------------------------

    /**
     * One query for the change log. "Reviewed in the window" is read from {@code updated_at}: a change
     * is edited when it is reviewed, and a later edit to a reviewed change is rare enough not to matter here.
     */
    private Changes changes(String projectId, LocalDate from, LocalDate today) {
        List<WorkItem> items = workItemRepository.findChangeLog(projectId, CHANGES_WORKFLOW,
                List.of(STATUS_PLANNED, STATUS_LIVE), STATUS_REVIEWED, from.atStartOfDay().atOffset(ZoneOffset.UTC));
        List<Change> open = new ArrayList<>();
        List<Change> due = new ArrayList<>();
        List<Change> reviewed = new ArrayList<>();
        for (WorkItem item : items) {
            Change change = toChange(item);
            switch (item.getCurrentStatus()) {
                case STATUS_PLANNED -> open.add(change);
                case STATUS_LIVE -> {
                    open.add(change);
                    if (item.getDueOn() != null && item.getDueOn().isBefore(today)) {
                        due.add(change);
                    }
                }
                case STATUS_REVIEWED -> reviewed.add(change);
                default -> { }
            }
        }
        return new Changes(open, due, reviewed);
    }

    private Change toChange(WorkItem item) {
        ChangeAssignee assignee = item.getAssignee() == null ? null
                : new ChangeAssignee(item.getAssignee().getId(), item.getAssignee().getName(),
                        item.getAssignee().getAvatarUrl());
        Map<String, Object> fields = item.getFields() == null || item.getFields().isEmpty() ? null
                : objectMapper.convertValue(item.getFields(), MAP);
        return new Change(item.getId(), item.getProject().getKey() + "-" + item.getSequenceNumber(), item.getTitle(),
                item.getType(), item.getCurrentStatus(), assignee, item.getDueOn(), fields);
    }
}
