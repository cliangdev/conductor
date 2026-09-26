package com.conductor.marketing.insights;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computes {@code GET /projects/{projectId}/marketing/insights}: a structured read of every
 * destination's latest performance snapshot in a window — totals, engagement rate sliced by platform,
 * format, hour and weekday, the best and worst destinations, and how the window compares with the one
 * before it.
 *
 * <p>Mirrors {@code PublishMetricsQueryService} in shape (plain records the controller maps to generated
 * DTOs, membership checked up front) but a different population: this reads destinations by when they
 * <em>fired</em>, not by when a metric was <em>observed</em>, because a window is about what published
 * in that stretch of time, not about which snapshots happened to land in it.
 */
@Service
public class MarketingInsightsQueryService {

    /** One window's bounds, echoed back so a client can render "Aug 17 – Sep 16". */
    public record Window(OffsetDateTime from, OffsetDateTime to, int days) {}

    /** One grouping's counters, summed over each of its destinations' latest available snapshot. */
    public record Group(String key, String label, int posts, long views, long likes, long comments,
                        long shares, long saves, Double engagementRate, Long medianViews, Double avgViewPct) {}

    /** One destination, for the top/bottom rankings. */
    public record Post(String workItemId, String displayId, String title, String workflowSlug,
                       String captionExcerpt, String targetId, String platform, String format,
                       String accountLabel, String permalink, OffsetDateTime firedAt, long views, Long likes,
                       Long comments, Long shares, Long saves, Double engagementRate, Double avgViewPct,
                       OffsetDateTime observedAt) {}

    /** One metric's current window vs. the window immediately before it. */
    public record Mover(String metric, double current, double previous, Double deltaPct) {}

    /** What is, and is not, being counted. */
    public record Coverage(List<String> platforms, List<String> notes) {}

    /** The whole response, as plain data the controller maps to {@code MarketingInsightsResponse}. */
    public record Insights(Window window, Group totals, List<Group> byPlatform, List<Group> byFormat,
                           List<Group> byHour, List<Group> byWeekday, List<Post> topPosts,
                           List<Post> bottomPosts, List<Mover> movers, Coverage coverage) {}

    /**
     * One destination in an insights population: its identity, when it fired, and — when it has reported
     * at least one usable snapshot — the counters of the latest one. {@code hasSnapshot} is false rather
     * than the counters simply being zero, because a destination that has not reported yet is not the
     * same as one that reported zero views.
     */
    private record TargetSnapshot(String targetId, String workItemId, String platform, String format,
                                  String accountLabel, String permalink, String captionOverride,
                                  OffsetDateTime fireTime, boolean hasSnapshot, Long views, Long likes,
                                  Long comments, Long shares, Long saves, Double avgViewPct,
                                  OffsetDateTime observedAt) {}

    private static final List<String> COUNT_METRICS = List.of("likes", "comments", "shares", "saves");
    private static final int TOP_POSTS_LIMIT = 10;
    private static final int BOTTOM_POSTS_LIMIT = 5;
    private static final int BOTTOM_POSTS_MIN_POPULATION = 10;
    private static final int CAPTION_EXCERPT_LENGTH = 140;

    /**
     * Mirrors {@code WorkItemWorkflowService.DEFAULT_WORKFLOW} (package-private there, so every package
     * outside {@code com.conductor.service} that needs it — {@code PublishingWorkflow},
     * {@code PublishPreflightService} — keeps its own copy rather than widening that field's visibility).
     */
    private static final String DEFAULT_WORKFLOW = "ENGINEERING";

    private final ProjectSecurityService projectSecurityService;
    private final PostPublishTargetRepository targetRepository;
    private final PostPublishTargetMetricRepository metricRepository;
    private final WorkItemRepository workItemRepository;
    private final PublishPlatformRegistry platformRegistry;

    public MarketingInsightsQueryService(ProjectSecurityService projectSecurityService,
                                         PostPublishTargetRepository targetRepository,
                                         PostPublishTargetMetricRepository metricRepository,
                                         WorkItemRepository workItemRepository,
                                         PublishPlatformRegistry platformRegistry) {
        this.projectSecurityService = projectSecurityService;
        this.targetRepository = targetRepository;
        this.metricRepository = metricRepository;
        this.workItemRepository = workItemRepository;
        this.platformRegistry = platformRegistry;
    }

    @Transactional(readOnly = true)
    public Insights compute(String projectId, String windowRaw, String platformRaw, User caller) {
        verifyMembership(projectId, caller);
        InsightsWindowSpec spec = InsightsWindowSpec.parse(windowRaw);
        String platform = normalizePlatform(platformRaw);
        return compute(projectId, spec, platform, OffsetDateTime.now());
    }

    /** Package-visible so a test can pin "now" without mocking the clock. */
    Insights compute(String projectId, InsightsWindowSpec spec, String platform, OffsetDateTime now) {
        OffsetDateTime from = spec.from(now);
        OffsetDateTime to = now;
        OffsetDateTime prevFrom = spec.previousFrom(now);
        OffsetDateTime prevTo = spec.previousTo(now);

        List<TargetSnapshot> population = loadPopulation(projectId, platform, from, to);
        List<TargetSnapshot> previousPopulation = loadPopulation(projectId, platform, prevFrom, prevTo);

        List<TargetSnapshot> reporting = population.stream().filter(TargetSnapshot::hasSnapshot).toList();
        List<TargetSnapshot> previousReporting = previousPopulation.stream().filter(TargetSnapshot::hasSnapshot).toList();

        Map<String, WorkItem> workItems = loadWorkItems(reporting);

        Group totals = groupOf("all", null, reporting);
        List<Group> byPlatform = groupBy(reporting, TargetSnapshot::platform, platformRegistry::labelOf);
        List<Group> byFormat = groupBy(reporting, TargetSnapshot::format, key -> null);
        List<Group> byHour = byHour(reporting);
        List<Group> byWeekday = byWeekday(reporting);

        List<Post> ranked = reporting.stream()
                .filter(t -> orZero(t.views()) >= 1)
                .map(t -> toPost(t, workItems.get(t.workItemId())))
                .sorted(Comparator.comparing(Post::engagementRate, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<Post> topPosts = ranked.stream().limit(TOP_POSTS_LIMIT).toList();
        List<Post> bottomPosts = reporting.size() >= BOTTOM_POSTS_MIN_POPULATION
                ? reversed(ranked).stream().limit(BOTTOM_POSTS_LIMIT).toList()
                : List.of();

        Group previousTotals = groupOf("all", null, previousReporting);
        List<Mover> movers = movers(totals, previousTotals);

        Coverage coverage = coverage(population, reporting);

        return new Insights(new Window(from, to, spec.days()), totals, byPlatform, byFormat, byHour, byWeekday,
                topPosts, bottomPosts, movers, coverage);
    }

    // ---- population -------------------------------------------------------------------------------

    private List<TargetSnapshot> loadPopulation(String projectId, String platform, OffsetDateTime from, OffsetDateTime to) {
        List<PostPublishTarget> targets = new ArrayList<>(
                targetRepository.findPublishedByFireTimeWindow(projectId, platform, from, to));

        List<PostPublishTarget> withoutFireTime = targetRepository.findPublishedWithoutFireTime(projectId, platform);
        if (!withoutFireTime.isEmpty()) {
            Map<String, OffsetDateTime> firstObservedAt = firstObservedAt(
                    withoutFireTime.stream().map(PostPublishTarget::getId).toList());
            for (PostPublishTarget target : withoutFireTime) {
                OffsetDateTime effective = firstObservedAt.get(target.getId());
                if (effective != null && !effective.isBefore(from) && effective.isBefore(to)) {
                    targets.add(target);
                }
            }
        }

        if (targets.isEmpty()) {
            return List.of();
        }

        List<String> targetIds = targets.stream().map(PostPublishTarget::getId).toList();
        Map<String, PostPublishTargetMetric> latest = metricRepository.findLatestAvailableForTargets(targetIds).stream()
                .collect(Collectors.toMap(PostPublishTargetMetric::getTargetId, m -> m));

        List<TargetSnapshot> result = new ArrayList<>();
        for (PostPublishTarget target : targets) {
            PostPublishTargetMetric metric = latest.get(target.getId());
            if (metric == null) {
                result.add(new TargetSnapshot(target.getId(), target.getWorkItem().getId(), target.getPlatform(),
                        target.getFormat(), target.getPlatformAccountLabel(), target.getPermalink(),
                        target.getCaptionOverride(), target.getFireTime(), false,
                        null, null, null, null, null, null, null));
            } else {
                result.add(new TargetSnapshot(target.getId(), target.getWorkItem().getId(), target.getPlatform(),
                        target.getFormat(), target.getPlatformAccountLabel(), target.getPermalink(),
                        target.getCaptionOverride(), target.getFireTime(), true,
                        metric.getViews(), metric.getLikes(), metric.getComments(), metric.getShares(),
                        metric.getSaves(), avgViewPct(metric.getExtra()), metric.getObservedAt()));
            }
        }
        return result;
    }

    private Map<String, OffsetDateTime> firstObservedAt(List<String> targetIds) {
        Map<String, OffsetDateTime> firstByTarget = new LinkedHashMap<>();
        for (PostPublishTargetMetric metric : metricRepository.findAllByTargetIdInOrderByTargetIdAscObservedAtAsc(targetIds)) {
            firstByTarget.putIfAbsent(metric.getTargetId(), metric.getObservedAt());
        }
        return firstByTarget;
    }

    private Map<String, WorkItem> loadWorkItems(List<TargetSnapshot> reporting) {
        List<String> workItemIds = reporting.stream().map(TargetSnapshot::workItemId).distinct().toList();
        if (workItemIds.isEmpty()) {
            return Map.of();
        }
        return workItemRepository.findAllById(workItemIds).stream()
                .collect(Collectors.toMap(WorkItem::getId, wi -> wi));
    }

    // ---- grouping -----------------------------------------------------------------------------------

    private List<Group> groupBy(List<TargetSnapshot> reporting, java.util.function.Function<TargetSnapshot, String> keyOf,
                                java.util.function.Function<String, String> labelOf) {
        Map<String, List<TargetSnapshot>> byKey = reporting.stream()
                .collect(Collectors.groupingBy(keyOf, LinkedHashMap::new, Collectors.toList()));
        return byKey.entrySet().stream()
                .map(e -> groupOf(e.getKey(), labelOf.apply(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing(Group::engagementRate, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private List<Group> byHour(List<TargetSnapshot> reporting) {
        Map<Integer, List<TargetSnapshot>> byHour = reporting.stream()
                .filter(t -> t.fireTime() != null)
                .collect(Collectors.groupingBy(t -> t.fireTime().withOffsetSameInstant(ZoneOffset.UTC).getHour()));
        return byHour.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> groupOf(String.valueOf(e.getKey()), null, e.getValue()))
                .toList();
    }

    private List<Group> byWeekday(List<TargetSnapshot> reporting) {
        Map<DayOfWeek, List<TargetSnapshot>> byWeekday = reporting.stream()
                .filter(t -> t.fireTime() != null)
                .collect(Collectors.groupingBy(t -> t.fireTime().withOffsetSameInstant(ZoneOffset.UTC).getDayOfWeek()));
        return byWeekday.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> groupOf(e.getKey().name(), null, e.getValue()))
                .toList();
    }

    private Group groupOf(String key, String label, List<TargetSnapshot> targets) {
        long views = 0;
        long likes = 0;
        long comments = 0;
        long shares = 0;
        long saves = 0;
        List<Long> viewsPerTarget = new ArrayList<>(targets.size());
        List<Double> avgViewPcts = new ArrayList<>();
        for (TargetSnapshot t : targets) {
            long targetViews = orZero(t.views());
            views += targetViews;
            likes += orZero(t.likes());
            comments += orZero(t.comments());
            shares += orZero(t.shares());
            saves += orZero(t.saves());
            viewsPerTarget.add(targetViews);
            if (t.avgViewPct() != null) {
                avgViewPcts.add(t.avgViewPct());
            }
        }
        Double engagementRate = views > 0 ? (double) (likes + comments + shares + saves) / views : null;
        Long medianViews = median(viewsPerTarget);
        Double avgViewPct = avgViewPcts.isEmpty() ? null
                : avgViewPcts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new Group(key, label, targets.size(), views, likes, comments, shares, saves, engagementRate,
                medianViews, avgViewPct);
    }

    private static Long median(List<Long> values) {
        if (values.isEmpty() || values.stream().noneMatch(v -> v > 0)) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : Math.round((sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0);
    }

    // ---- top / bottom posts ---------------------------------------------------------------------------

    private Post toPost(TargetSnapshot t, WorkItem workItem) {
        long views = orZero(t.views());
        long likes = orZero(t.likes());
        long comments = orZero(t.comments());
        long shares = orZero(t.shares());
        long saves = orZero(t.saves());
        Double engagementRate = views > 0 ? (double) (likes + comments + shares + saves) / views : null;
        String displayId = displayId(workItem);
        String caption = excerpt(t.captionOverride() != null ? t.captionOverride()
                : workItem != null ? workItem.getDescription() : null);
        String workflowSlug = workItem != null
                ? (workItem.getWorkflow() != null ? workItem.getWorkflow() : DEFAULT_WORKFLOW)
                : null;
        return new Post(t.workItemId(), displayId, workItem != null ? workItem.getTitle() : null, workflowSlug,
                caption, t.targetId(), t.platform(), t.format(), t.accountLabel(), t.permalink(), t.fireTime(),
                views, t.likes(), t.comments(), t.shares(), t.saves(), engagementRate, t.avgViewPct(),
                t.observedAt());
    }

    private static String displayId(WorkItem workItem) {
        if (workItem == null || workItem.getProject() == null || workItem.getProject().getKey() == null
                || workItem.getSequenceNumber() == null) {
            return null;
        }
        return workItem.getProject().getKey() + "-" + workItem.getSequenceNumber();
    }

    private static String excerpt(String raw) {
        if (raw == null) {
            return null;
        }
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        if (collapsed.isEmpty()) {
            return null;
        }
        return collapsed.length() <= CAPTION_EXCERPT_LENGTH ? collapsed : collapsed.substring(0, CAPTION_EXCERPT_LENGTH);
    }

    private static <T> List<T> reversed(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        java.util.Collections.reverse(copy);
        return copy;
    }

    // ---- movers -----------------------------------------------------------------------------------

    private List<Mover> movers(Group current, Group previous) {
        List<Mover> movers = new ArrayList<>();
        movers.add(mover("posts", current.posts(), previous.posts()));
        movers.add(mover("views", current.views(), previous.views()));
        movers.add(mover("likes", current.likes(), previous.likes()));
        movers.add(mover("comments", current.comments(), previous.comments()));
        movers.add(mover("shares", current.shares(), previous.shares()));
        movers.add(mover("saves", current.saves(), previous.saves()));
        movers.add(mover("engagementRate", orZero(current.engagementRate()), orZero(previous.engagementRate())));
        return movers;
    }

    private static Mover mover(String metric, double current, double previous) {
        Double deltaPct = previous == 0 ? null : (current - previous) / previous * 100;
        return new Mover(metric, current, previous, deltaPct);
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    // ---- coverage -----------------------------------------------------------------------------------

    private Coverage coverage(List<TargetSnapshot> population, List<TargetSnapshot> reporting) {
        Map<String, List<TargetSnapshot>> populationByPlatform = population.stream()
                .collect(Collectors.groupingBy(TargetSnapshot::platform, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<TargetSnapshot>> reportingByPlatform = reporting.stream()
                .collect(Collectors.groupingBy(TargetSnapshot::platform, LinkedHashMap::new, Collectors.toList()));

        // Deterministic order: the registry's picker order, then anything it doesn't know about.
        List<String> platformOrder = new ArrayList<>(platformRegistry.ids());
        for (String platform : populationByPlatform.keySet()) {
            if (!platformOrder.contains(platform)) {
                platformOrder.add(platform);
            }
        }

        List<String> platforms = new LinkedHashSet<>(platformOrder).stream()
                .filter(reportingByPlatform::containsKey)
                .toList();

        List<String> notes = new ArrayList<>();
        for (String platform : platformOrder) {
            List<TargetSnapshot> forPlatform = populationByPlatform.get(platform);
            if (forPlatform == null) {
                continue;
            }
            String label = platformRegistry.labelOf(platform);
            long missingViews = forPlatform.stream()
                    .filter(t -> !t.hasSnapshot() || t.views() == null)
                    .count();
            if (missingViews > 0) {
                notes.add(label + " reported no views for " + missingViews + " of " + forPlatform.size()
                        + " destinations.");
            }
            List<TargetSnapshot> reportingForPlatform = reportingByPlatform.getOrDefault(platform, List.of());
            if (!reportingForPlatform.isEmpty()) {
                for (String metric : COUNT_METRICS) {
                    if (reportingForPlatform.stream().allMatch(t -> metricValue(t, metric) == null)) {
                        notes.add(label + " does not report " + metric + ".");
                    }
                }
            }
        }
        return new Coverage(platforms, notes);
    }

    private static Long metricValue(TargetSnapshot t, String metric) {
        return switch (metric) {
            case "likes" -> t.likes();
            case "comments" -> t.comments();
            case "shares" -> t.shares();
            case "saves" -> t.saves();
            default -> throw new IllegalArgumentException("Unknown metric " + metric);
        };
    }

    // ---- misc ---------------------------------------------------------------------------------------

    private static Double avgViewPct(JsonNode extra) {
        if (extra == null) {
            return null;
        }
        JsonNode node = extra.get("avg_view_pct");
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private String normalizePlatform(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (platformRegistry.find(normalized).isEmpty()) {
            throw new BusinessException("Unknown platform '" + raw + "'");
        }
        return normalized;
    }

    private void verifyMembership(String projectId, User caller) {
        if (caller == null || !projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }
}
