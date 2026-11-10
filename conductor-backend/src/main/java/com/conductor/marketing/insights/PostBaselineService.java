package com.conductor.marketing.insights;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The account's own yardstick for "is this post typical?": per project, the median views and median
 * average-view-percentage of settled reporting destinations over the trailing {@value #BASELINE_DAYS} days,
 * kept per platform and format, falling back to the platform alone, then to nothing. A post's <em>view
 * lift</em> is its views over the baseline median (1.0 is typical); its <em>retention lift</em> is its
 * avgViewPct over the baseline's. Both are null when either side is missing. The window is independent of
 * any insights window. Shared by marketing insights' {@code byTag}, per-Creative performance and the weekly
 * snapshot, so all three speak the same lift.
 */
@Service
public class PostBaselineService {

    /** Trailing days of settled posts the baseline is the median of. */
    public static final int BASELINE_DAYS = 90;

    /** Settled posts a bucket needs before its own median is used (platform+format, then platform). */
    public static final int MIN_BUCKET_POSTS = 10;

    /** One reporting destination's numbers. */
    public record Observation(String targetId, String platform, String format, OffsetDateTime fireTime, Long views, Long likes,
                              Long comments, Long shares, Long saves, Double avgViewPct) {

        Double engagementRate() {
            if (views == null || views <= 0) {
                return null;
            }
            long engagement = (likes == null ? 0 : likes) + (comments == null ? 0 : comments)
                    + (shares == null ? 0 : shares) + (saves == null ? 0 : saves);
            return (double) engagement / views;
        }
    }

    /** A bucket's medians; either may be null (no destination reported it). */
    public record Baseline(Double medianViews, Double medianAvgViewPct) {}

    /** The per-post lifts plus engagement rate, any null when not computable. */
    public record PostLift(Double viewLift, Double retentionLift, Double engagementRate) {}

    /**
     * Medians over a set of settled posts: {@code medianViewLift}, {@code beatBaselineShare} (share of posts
     * with a lift whose lift is above 1), {@code medianEngagementRate} (median of per-post rates, not pooled)
     * and {@code medianRetentionLift}. Each null when it cannot be computed.
     */
    public record LiftSummary(Double medianViewLift, Double beatBaselineShare, Double medianEngagementRate,
                              Double medianRetentionLift, int withBaselinePosts, int beatBaselinePosts,
                              int withRetentionBaselinePosts, int beatRetentionBaselinePosts, String viewVerdict,
                              String retentionVerdict) {}

    /** Baselines for one project at one moment. */
    public static final class Baselines {
        private final Map<String, Baseline> byPlatformFormat;
        private final Map<String, Baseline> byPlatform;
        private final List<Observation> settled;
        private final List<Observation> unsettled;
        private final OffsetDateTime from;
        private final OffsetDateTime to;

        Baselines(Map<String, Baseline> byPlatformFormat, Map<String, Baseline> byPlatform,
                  List<Observation> settled, List<Observation> unsettled, OffsetDateTime from, OffsetDateTime to) {
            this.byPlatformFormat = byPlatformFormat;
            this.byPlatform = byPlatform;
            this.settled = settled;
            this.unsettled = unsettled;
            this.from = from;
            this.to = to;
        }

        /** No evidence at all (no baseline, no posts), for the window ending at {@code now}. */
        public static Baselines empty(OffsetDateTime now) {
            return new Baselines(Map.of(), Map.of(), List.of(), List.of(), now.minusDays(BASELINE_DAYS), now);
        }

        /** Settled reporting destinations of the trailing {@value #BASELINE_DAYS} days: the one fixed evidence set. */
        public List<Observation> settled() { return settled; }

        /** Reporting destinations of the same days still inside their settle window. */
        public List<Observation> unsettled() { return unsettled; }

        public OffsetDateTime from() { return from; }

        public OffsetDateTime to() { return to; }

        /** The platform+format baseline when it has enough posts, else the platform's, else null. */
        public Baseline forBucket(String platform, String format) {
            Baseline b = byPlatformFormat.get(key(platform, format));
            return b != null ? b : byPlatform.get(platform);
        }

        public PostLift lift(Observation o) {
            Baseline b = forBucket(o.platform(), o.format());
            Double viewLift = null;
            Double retentionLift = null;
            if (b != null) {
                if (o.views() != null && b.medianViews() != null && b.medianViews() > 0) {
                    viewLift = o.views() / b.medianViews();
                }
                if (o.avgViewPct() != null && b.medianAvgViewPct() != null && b.medianAvgViewPct() > 0) {
                    retentionLift = o.avgViewPct() / b.medianAvgViewPct();
                }
            }
            return new PostLift(viewLift, retentionLift, o.engagementRate());
        }

        public LiftSummary summarize(List<Observation> settled) {
            List<PostLift> lifts = settled.stream().map(this::lift).toList();
            List<Double> viewLifts = lifts.stream().map(PostLift::viewLift).filter(java.util.Objects::nonNull).toList();
            List<Double> retentionLifts = lifts.stream().map(PostLift::retentionLift).filter(java.util.Objects::nonNull).toList();
            int beat = (int) viewLifts.stream().filter(l -> l > 1).count();
            int beatRetention = (int) retentionLifts.stream().filter(l -> l > 1).count();
            Double medianView = median(viewLifts);
            Double medianRetention = median(retentionLifts);
            return new LiftSummary(medianView, viewLifts.isEmpty() ? null : (double) beat / viewLifts.size(),
                    median(lifts.stream().map(PostLift::engagementRate).filter(java.util.Objects::nonNull).toList()),
                    medianRetention, viewLifts.size(), beat, retentionLifts.size(), beatRetention,
                    LessonVerdictRules.verdict(viewLifts.size(), beat, medianView),
                    LessonVerdictRules.verdict(retentionLifts.size(), beatRetention, medianRetention));
        }
    }

    private final PostPublishTargetRepository targetRepository;
    private final PostPublishTargetMetricRepository metricRepository;

    public PostBaselineService(PostPublishTargetRepository targetRepository,
                               PostPublishTargetMetricRepository metricRepository) {
        this.targetRepository = targetRepository;
        this.metricRepository = metricRepository;
    }

    /**
     * The evidence for one project at one moment, always the trailing {@value #BASELINE_DAYS} days whatever
     * window a caller is looking at: two queries however many posts (published destinations, latest snapshots).
     * Build it once per request and pass it down.
     */
    @Transactional(readOnly = true)
    public Baselines baselines(String projectId, OffsetDateTime now) {
        OffsetDateTime from = now.minusDays(BASELINE_DAYS);
        List<PostPublishTarget> targets = targetRepository.findPublishedByFireTimeWindow(projectId, null, from, now);
        if (targets.isEmpty()) {
            return Baselines.empty(now);
        }
        Map<String, PostPublishTargetMetric> latest = metricRepository
                .findLatestAvailableForTargets(targets.stream().map(PostPublishTarget::getId).toList()).stream()
                .collect(Collectors.toMap(PostPublishTargetMetric::getTargetId, m -> m));

        List<Observation> settled = new ArrayList<>();
        List<Observation> unsettled = new ArrayList<>();
        for (PostPublishTarget t : targets) {
            PostPublishTargetMetric m = latest.get(t.getId());
            if (m == null) {
                continue;
            }
            Observation o = new Observation(t.getId(), t.getPlatform(), t.getFormat(), t.getFireTime(), m.getViews(),
                    m.getLikes(), m.getComments(), m.getShares(), m.getSaves(), avgViewPct(m.getExtra()));
            (PostSettleWindows.isSettled(t.getPlatform(), t.getFireTime(), now) ? settled : unsettled).add(o);
        }

        Map<String, List<Observation>> byPf = new HashMap<>();
        Map<String, List<Observation>> byP = new HashMap<>();
        for (Observation o : settled) {
            byPf.computeIfAbsent(key(o.platform(), o.format()), k -> new ArrayList<>()).add(o);
            byP.computeIfAbsent(o.platform(), k -> new ArrayList<>()).add(o);
        }
        return new Baselines(baselinesOf(byPf), baselinesOf(byP), settled, unsettled, from, now);
    }

    private static Map<String, Baseline> baselinesOf(Map<String, List<Observation>> buckets) {
        Map<String, Baseline> out = new HashMap<>();
        buckets.forEach((k, observations) -> {
            if (observations.size() < MIN_BUCKET_POSTS) {
                return;
            }
            out.put(k, new Baseline(
                    median(observations.stream().map(Observation::views).filter(java.util.Objects::nonNull)
                            .map(Long::doubleValue).toList()),
                    median(observations.stream().map(Observation::avgViewPct).filter(java.util.Objects::nonNull).toList())));
        });
        return out;
    }

    static String key(String platform, String format) {
        return platform + "|" + (format == null ? "" : format);
    }

    public static Double avgViewPct(JsonNode extra) {
        if (extra == null) {
            return null;
        }
        JsonNode node = extra.get("avg_view_pct");
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    /** The median of the values (mean of the middle two for an even count); null for none. */
    public static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
