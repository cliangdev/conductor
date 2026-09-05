package com.conductor.service.publish;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProjectSecurityService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Reads back what the {@code post_metrics} feeds filed: one Post's series per destination, and the
 * project's best-performing destinations by one metric.
 */
@Service
public class PublishMetricsQueryService {

    /** A snapshot as a client sees it. */
    public record Snapshot(OffsetDateTime observedAt, Long views, Long likes, Long comments, Long shares,
                           Long saves, Long reach, Long impressions, Long watchTimeSeconds, boolean unavailable) {

        static Snapshot of(PostPublishTargetMetric m) {
            return new Snapshot(m.getObservedAt(), m.getViews(), m.getLikes(), m.getComments(), m.getShares(),
                    m.getSaves(), m.getReach(), m.getImpressions(), m.getWatchTimeSeconds(), m.isUnavailable());
        }
    }

    /** One destination's series. */
    public record TargetMetrics(String targetId, String platform, String accountLabel, String permalink,
                                Snapshot latest, List<Snapshot> series) {}

    /** Everything a Post page shows: per destination, plus the sum of the latest snapshots. */
    public record PostMetrics(String workItemId, List<TargetMetrics> targets, Snapshot totals) {}

    /** One row of the project ranking. */
    public record TopPost(String workItemId, String displayId, String title, String targetId, String platform,
                          String accountLabel, String permalink, String metric, long value, OffsetDateTime observedAt) {}

    private static final Map<String, ToLongFunction<PostPublishTargetMetric>> METRICS = Map.of(
            "views", m -> orZero(m.getViews()),
            "likes", m -> orZero(m.getLikes()),
            "comments", m -> orZero(m.getComments()),
            "shares", m -> orZero(m.getShares()),
            "saves", m -> orZero(m.getSaves()),
            "reach", m -> orZero(m.getReach()),
            "impressions", m -> orZero(m.getImpressions()));

    private final ProjectSecurityService projectSecurityService;
    private final WorkItemRepository workItemRepository;
    private final PostPublishTargetRepository targetRepository;
    private final PostPublishTargetMetricRepository metricRepository;

    public PublishMetricsQueryService(ProjectSecurityService projectSecurityService,
                                      WorkItemRepository workItemRepository,
                                      PostPublishTargetRepository targetRepository,
                                      PostPublishTargetMetricRepository metricRepository) {
        this.projectSecurityService = projectSecurityService;
        this.workItemRepository = workItemRepository;
        this.targetRepository = targetRepository;
        this.metricRepository = metricRepository;
    }

    /** The metric names a ranking accepts. */
    public static List<String> metricNames() {
        return List.of("views", "likes", "comments", "shares", "saves", "reach", "impressions");
    }

    @Transactional(readOnly = true)
    public PostMetrics forPost(String projectId, String workItemId, OffsetDateTime since, User caller) {
        verifyMembership(projectId, caller);
        WorkItem post = workItemRepository.findById(workItemId)
                .filter(item -> item.getProject() != null && projectId.equals(item.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));

        Map<String, List<PostPublishTargetMetric>> byTarget = new LinkedHashMap<>();
        for (PostPublishTargetMetric m : metricRepository.findAllByWorkItemIdOrderByObservedAtAsc(post.getId())) {
            if (since != null && m.getObservedAt().isBefore(since)) {
                continue;
            }
            byTarget.computeIfAbsent(m.getTargetId(), k -> new ArrayList<>()).add(m);
        }
        Map<String, PostPublishTarget> targets = new LinkedHashMap<>();
        for (PostPublishTarget target : targetRepository.findAllByWorkItemId(post.getId())) {
            targets.put(target.getId(), target);
        }

        List<TargetMetrics> result = new ArrayList<>();
        long[] totals = new long[8];
        boolean any = false;
        OffsetDateTime latestAt = null;
        for (Map.Entry<String, List<PostPublishTargetMetric>> entry : byTarget.entrySet()) {
            PostPublishTarget target = targets.get(entry.getKey());
            List<PostPublishTargetMetric> rows = entry.getValue();
            PostPublishTargetMetric last = rows.get(rows.size() - 1);
            result.add(new TargetMetrics(entry.getKey(),
                    target != null ? target.getPlatform() : last.getPlatform(),
                    target != null ? target.getPlatformAccountLabel() : null,
                    target != null ? target.getPermalink() : null,
                    Snapshot.of(last),
                    rows.stream().map(Snapshot::of).toList()));
            if (!last.isUnavailable()) {
                any = true;
                totals[0] += orZero(last.getViews());
                totals[1] += orZero(last.getLikes());
                totals[2] += orZero(last.getComments());
                totals[3] += orZero(last.getShares());
                totals[4] += orZero(last.getSaves());
                totals[5] += orZero(last.getReach());
                totals[6] += orZero(last.getImpressions());
                totals[7] += orZero(last.getWatchTimeSeconds());
                if (latestAt == null || last.getObservedAt().isAfter(latestAt)) {
                    latestAt = last.getObservedAt();
                }
            }
        }
        Snapshot totalsSnapshot = any
                ? new Snapshot(latestAt, totals[0], totals[1], totals[2], totals[3], totals[4], totals[5], totals[6], totals[7], false)
                : null;
        return new PostMetrics(post.getId(), result, totalsSnapshot);
    }

    @Transactional(readOnly = true)
    public List<TopPost> topPosts(String projectId, String metric, String platform, OffsetDateTime since,
                                  int limit, User caller) {
        verifyMembership(projectId, caller);
        String metricName = metric == null || metric.isBlank() ? "views" : metric.trim().toLowerCase(Locale.ROOT);
        ToLongFunction<PostPublishTargetMetric> read = METRICS.get(metricName);
        if (read == null) {
            throw new IllegalArgumentException("Unknown metric '" + metric + "'; one of " + metricNames());
        }
        String platformFilter = platform == null || platform.isBlank() ? null : platform.trim().toLowerCase(Locale.ROOT);
        List<PostPublishTargetMetric> latest = metricRepository.findLatestPerTarget(projectId, platformFilter, since);
        List<TopPost> rows = new ArrayList<>();
        for (PostPublishTargetMetric m : latest) {
            if (m.isUnavailable()) {
                continue;
            }
            PostPublishTarget target = targetRepository.findById(m.getTargetId()).orElse(null);
            WorkItem post = target != null ? target.getWorkItem() : workItemRepository.findById(m.getWorkItemId()).orElse(null);
            String displayId = post != null && post.getProject() != null && post.getProject().getKey() != null
                    && post.getSequenceNumber() != null
                    ? post.getProject().getKey() + "-" + post.getSequenceNumber() : null;
            rows.add(new TopPost(m.getWorkItemId(), displayId, post != null ? post.getTitle() : null,
                    m.getTargetId(), m.getPlatform(),
                    target != null ? target.getPlatformAccountLabel() : null,
                    target != null ? target.getPermalink() : null,
                    metricName, read.applyAsLong(m), m.getObservedAt()));
        }
        rows.sort(Comparator.comparingLong(TopPost::value).reversed());
        int cap = Math.max(1, Math.min(limit, 100));
        return rows.size() > cap ? rows.subList(0, cap) : rows;
    }

    private void verifyMembership(String projectId, User caller) {
        if (caller == null || !projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
