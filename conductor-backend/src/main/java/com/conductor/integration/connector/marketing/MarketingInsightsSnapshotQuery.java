package com.conductor.integration.connector.marketing;

import com.conductor.creative.CreativeTagAttribution;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.WorkItem;
import com.conductor.marketing.insights.PostBaselineService;
import com.conductor.marketing.insights.PostSettleWindows;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads {@code post_publish_target}/{@code post_publish_target_metric} into the weekly snapshot
 * payload {@link MarketingInsightsConnector#fetchData} hands to the digest pipeline. Two queries,
 * no N+1: one for the window's published destinations (with their Work Item eagerly fetched), one for
 * the latest non-{@code unavailable} snapshot of each.
 *
 * <p>A destination belongs to the day its {@code fireTime} (UTC) falls on, and contributes its latest
 * available snapshot — not a per-day delta, since a platform reports lifetime-to-date counts, not
 * daily deltas. A destination that fired in the window but has never reported an available snapshot
 * (still pending, or every pull came back unavailable) is simply absent from the metric totals, same
 * as {@link PostPublishTargetMetricRepository#findLatestAvailableForTargets}'s own contract.
 *
 * <p>The payload also carries the marketing changes whose review date has passed with no verdict
 * ({@code changesDueForReview}), so the weekly digest can name them. They are read live, not from the
 * trailing window: a change is due for as long as nobody has recorded its verdict.
 */
@Component
public class MarketingInsightsSnapshotQuery {

    private static final int WINDOW_DAYS = 7;
    private static final int TOP_POSTS_LIMIT = 10;
    private static final int DUE_CHANGES_LIMIT = 20;
    private static final String CHANGES_WORKFLOW = "MARKETING_CHANGES";
    static final String DUE_CHANGES_LABEL = "Changes past their review date with no verdict";

    private final PostPublishTargetRepository targetRepository;
    private final PostPublishTargetMetricRepository metricRepository;
    private final WorkItemRepository workItemRepository;
    private final CreativeTagAttribution tagAttribution;
    private final PostBaselineService baselineService;

    public MarketingInsightsSnapshotQuery(PostPublishTargetRepository targetRepository,
                                          PostPublishTargetMetricRepository metricRepository,
                                          WorkItemRepository workItemRepository,
                                          CreativeTagAttribution tagAttribution,
                                          PostBaselineService baselineService) {
        this.tagAttribution = tagAttribution;
        this.baselineService = baselineService;
        this.targetRepository = targetRepository;
        this.metricRepository = metricRepository;
        this.workItemRepository = workItemRepository;
    }

    /** The trailing 7 full UTC days, half-open, ending at the start of today (UTC). */
    public static Instant[] trailingWindow(Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant end = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant start = today.minusDays(WINDOW_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new Instant[] { start, end };
    }

    public Map<String, Object> fetch(String projectId, Instant now) {
        Instant[] window = trailingWindow(now);
        OffsetDateTime from = window[0].atOffset(ZoneOffset.UTC);
        OffsetDateTime to = window[1].atOffset(ZoneOffset.UTC);

        List<PostPublishTarget> targets =
                targetRepository.findPublishedWithWorkItemByFireTimeWindow(projectId, from, to);

        Map<String, PostPublishTargetMetric> latestByTarget = new LinkedHashMap<>();
        if (!targets.isEmpty()) {
            List<String> targetIds = targets.stream().map(PostPublishTarget::getId).toList();
            for (PostPublishTargetMetric m : metricRepository.findLatestAvailableForTargets(targetIds)) {
                latestByTarget.put(m.getTargetId(), m);
            }
        }

        Map<String, String> hookTypeByTarget = hookTypes(projectId, targets);
        Map<String, Object> payload = build(targets, latestByTarget, window[0], window[1], hookTypeByTarget,
                hookTypeByTarget.isEmpty() ? null : baselineService.baselines(projectId, now.atOffset(ZoneOffset.UTC)),
                now.atOffset(ZoneOffset.UTC));
        Map<String, Object> dueChanges = changesDueForReview(projectId, now);
        if (dueChanges != null) {
            payload.put("changesDueForReview", dueChanges);
        }
        return payload;
    }

    /**
     * Destination id to the hookType tag of the Creative it was published from (through the shared
     * attribution); destinations with no attributed Creative or no hookType are absent. A destination
     * attributed to several hookTypes counts under the alphabetically first, once, so rows still sum to posts.
     */
    private Map<String, String> hookTypes(String projectId, List<PostPublishTarget> targets) {
        if (targets.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        tagAttribution.tagsByTarget(projectId).forEach((targetId, tags) -> tags.stream()
                .filter(t -> "hookType".equals(t.key()))
                .map(CreativeTagAttribution.TagValue::value)
                .min(Comparator.naturalOrder())
                .ifPresent(v -> result.put(targetId, v)));
        return result;
    }

    /**
     * The project's MARKETING_CHANGES items still PLANNED or LIVE whose review date is before today (UTC) and
     * that have no verdict, oldest review date first, capped at {@value #DUE_CHANGES_LIMIT} with the full
     * {@code count}. Null when there are none, so a quiet project's payload is unchanged. A reviewed change
     * is never listed, and neither is one with no review date: it is not due until somebody says when.
     */
    private Map<String, Object> changesDueForReview(String projectId, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        // An empty reviewed window (since == until): only the open statuses come back.
        OffsetDateTime never = Instant.EPOCH.atOffset(ZoneOffset.UTC);
        List<WorkItem> due = workItemRepository
                .findChangeLog(projectId, CHANGES_WORKFLOW, List.of("PLANNED", "LIVE"), "REVIEWED", never, never)
                .stream()
                .filter(item -> item.getDueOn() != null && item.getDueOn().isBefore(today))
                .filter(item -> !hasVerdict(item))
                .sorted(Comparator.comparing(WorkItem::getDueOn).thenComparing(WorkItem::getSequenceNumber))
                .toList();
        if (due.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorkItem item : due.stream().limit(DUE_CHANGES_LIMIT).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("displayId", item.getProject().getKey() + "-" + item.getSequenceNumber());
            row.put("title", item.getTitle());
            row.put("status", item.getCurrentStatus());
            row.put("dueOn", item.getDueOn().toString());
            rows.add(row);
        }
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("label", DUE_CHANGES_LABEL);
        section.put("count", due.size());
        section.put("changes", rows);
        return section;
    }

    private static boolean hasVerdict(WorkItem item) {
        return item.getFields() != null && item.getFields().hasNonNull("verdict")
                && !item.getFields().get("verdict").asText().isBlank();
    }

    private Map<String, Object> build(List<PostPublishTarget> targets,
                                      Map<String, PostPublishTargetMetric> latestByTarget,
                                      Instant windowStart, Instant windowEnd,
                                      Map<String, String> hookTypeByTarget,
                                      PostBaselineService.Baselines baselines, OffsetDateTime now) {
        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;

        // Zero-filled day buckets across the whole window, in order.
        Map<String, long[]> byDay = new LinkedHashMap<>(); // [posts, views, likes, comments, shares, saves, engagements]
        for (LocalDate d = windowStart.atZone(ZoneOffset.UTC).toLocalDate();
             d.isBefore(windowEnd.atZone(ZoneOffset.UTC).toLocalDate()); d = d.plusDays(1)) {
            byDay.put(d.format(iso), new long[7]);
        }

        Map<String, long[]> byPlatform = new LinkedHashMap<>(); // [posts, views, engagements]
        Map<String, long[]> byFormat = new LinkedHashMap<>();
        Map<String, long[]> byHookType = new LinkedHashMap<>();
        Map<String, List<PostBaselineService.Observation>> hookObservations = new LinkedHashMap<>();
        List<Map<String, Object>> candidatePosts = new ArrayList<>();

        for (PostPublishTarget target : targets) {
            PostPublishTargetMetric metric = latestByTarget.get(target.getId());
            long views = metric != null ? orZero(metric.getViews()) : 0;
            long likes = metric != null ? orZero(metric.getLikes()) : 0;
            long comments = metric != null ? orZero(metric.getComments()) : 0;
            long shares = metric != null ? orZero(metric.getShares()) : 0;
            long saves = metric != null ? orZero(metric.getSaves()) : 0;
            long engagements = likes + comments + shares + saves;

            String day = target.getFireTime().atZoneSameInstant(ZoneOffset.UTC).toLocalDate().format(iso);
            long[] bucket = byDay.get(day);
            if (bucket != null) {
                bucket[0] += 1;
                bucket[1] += views;
                bucket[2] += likes;
                bucket[3] += comments;
                bucket[4] += shares;
                bucket[5] += saves;
                bucket[6] += engagements;
            }

            if (metric != null) {
                accumulate(byPlatform, target.getPlatform(), views, engagements);
                accumulate(byFormat, target.getFormat(), views, engagements);
                String hookType = hookTypeByTarget.get(target.getId());
                // Settled posts only: a post a few hours old has not finished collecting views.
                if (hookType != null && PostSettleWindows.isSettled(target.getPlatform(), target.getFireTime(), now)) {
                    accumulate(byHookType, hookType, views, engagements);
                    hookObservations.computeIfAbsent(hookType, k -> new ArrayList<>()).add(
                            new PostBaselineService.Observation(target.getPlatform(), target.getFormat(),
                                    target.getFireTime(), metric.getViews(), metric.getLikes(), metric.getComments(),
                                    metric.getShares(), metric.getSaves(),
                                    PostBaselineService.avgViewPct(metric.getExtra())));
                }

                if (views >= 1) {
                    WorkItem post = target.getWorkItem();
                    String displayId = post.getProject() != null && post.getProject().getKey() != null
                            && post.getSequenceNumber() != null
                            ? post.getProject().getKey() + "-" + post.getSequenceNumber() : post.getId();
                    String label = displayId + " · " + capitalize(target.getPlatform()) + " · " + post.getTitle();
                    double rate = views > 0 ? (double) engagements / views : 0.0;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("post", label);
                    row.put("views", views);
                    row.put("engagementRate", rate);
                    row.put("permalink", target.getPermalink());
                    candidatePosts.add(row);
                }
            }
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        byDay.forEach((date, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date);
            row.put("posts", v[0]);
            row.put("views", v[1]);
            row.put("likes", v[2]);
            row.put("comments", v[3]);
            row.put("shares", v[4]);
            row.put("saves", v[5]);
            row.put("engagements", v[6]);
            trend.add(row);
        });

        List<Map<String, Object>> platformRows = toDimensionRows(byPlatform, "platform");
        List<Map<String, Object>> formatRows = toDimensionRows(byFormat, "format");

        List<Map<String, Object>> topPosts = candidatePosts.stream()
                .sorted(Comparator.comparingDouble((Map<String, Object> r) -> (double) r.get("engagementRate")).reversed())
                .limit(TOP_POSTS_LIMIT)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trend", trend);
        payload.put("byPlatform", platformRows);
        payload.put("byFormat", formatRows);
        List<Map<String, Object>> hookRows = toDimensionRows(byHookType, "hookType");
        for (Map<String, Object> row : hookRows) {
            Double lift = baselines == null ? null
                    : baselines.summarize(hookObservations.get((String) row.get("hookType"))).medianViewLift();
            if (lift != null) {
                row.put("medianViewLift", lift);
            }
        }
        payload.put("byHookType", hookRows);
        payload.put("topPosts", topPosts);
        return payload;
    }

    private void accumulate(Map<String, long[]> totals, String key, long views, long engagements) {
        long[] bucket = totals.computeIfAbsent(key, k -> new long[3]);
        bucket[0] += 1;
        bucket[1] += views;
        bucket[2] += engagements;
    }

    private List<Map<String, Object>> toDimensionRows(Map<String, long[]> totals, String idField) {
        List<Map<String, Object>> rows = new ArrayList<>();
        totals.forEach((key, v) -> {
            long posts = v[0];
            long views = v[1];
            long engagements = v[2];
            double rate = views > 0 ? (double) engagements / views : 0.0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(idField, key);
            row.put("posts", posts);
            row.put("views", views);
            row.put("engagements", engagements);
            row.put("engagementRate", rate);
            rows.add(row);
        });
        rows.sort(Comparator.comparingDouble((Map<String, Object> r) -> (double) r.get("engagementRate")).reversed());
        return rows;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
