package com.conductor.creative;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.User;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.service.ProjectSecurityService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-creative and per-variant performance roll-ups (COND-24 T5): attribution through {@link
 * CreativeAttributionResolver}, aggregated the same way {@code MarketingInsightsQueryService} aggregates
 * a group — posts, views, engagement rate {@code (likes+comments+shares+saves)/views}, average view
 * percentage where a platform reports it, and 72h views (the snapshot nearest at or after {@code
 * fireTime + 72h}), sliced by platform. See {@code docs/creatives.md}'s "Performance and experiments".
 */
@Service
public class CreativePerformanceService {

    private static final int VIEWS_72H_HOURS = 72;

    /** One platform's slice of one variant's numbers. */
    public record PlatformBreakdown(String platform, int posts, long views, Double engagementRate) {}

    /** One variant's roll-up — a row of {@code CreativeResponse}'s family, or of insights' top creatives. */
    public record VariantPerformance(String creativeId, String label, String headline, int posts, long views,
                                     Double engagementRate, Double avgViewPct, Long views72h,
                                     List<PlatformBreakdown> byPlatform) {}

    /** The whole family (the requested Creative plus every lettered sibling), oldest letter first. */
    public record FamilyPerformance(String creativeId, List<VariantPerformance> family) {}

    private final CreativeRepository creativeRepository;
    private final CreativeAttributionResolver attributionResolver;
    private final PostPublishTargetMetricRepository metricRepository;
    private final ProjectSecurityService projectSecurityService;

    public CreativePerformanceService(CreativeRepository creativeRepository,
                                      CreativeAttributionResolver attributionResolver,
                                      PostPublishTargetMetricRepository metricRepository,
                                      ProjectSecurityService projectSecurityService) {
        this.creativeRepository = creativeRepository;
        this.attributionResolver = attributionResolver;
        this.metricRepository = metricRepository;
        this.projectSecurityService = projectSecurityService;
    }

    @Transactional(readOnly = true)
    public FamilyPerformance familyPerformance(String projectId, String creativeId, User caller) {
        requireMember(projectId, caller);
        Creative creative = findCreative(projectId, creativeId);
        Creative root = rootOf(projectId, creative);
        List<Creative> family = new ArrayList<>(creativeRepository.findAllByNumberAndProjectId(root.getNumber(), projectId));
        family.sort(Comparator.comparing(Creative::getVariantLetter));
        List<VariantPerformance> perf = family.stream().map(c -> variantPerformance(c, null, null)).toList();
        return new FamilyPerformance(creativeId, perf);
    }

    /**
     * Every creative in the project with at least one published, attributed destination in {@code
     * [from, to)} — the "top creatives" insights sees. {@code from}/{@code to} both null means all time.
     * Best views first.
     */
    @Transactional(readOnly = true)
    public List<VariantPerformance> topCreatives(String projectId, OffsetDateTime from, OffsetDateTime to, int limit) {
        List<Creative> creatives = creativeRepository.findAllByProjectIdOrderByNumberDescVariantLetterAsc(projectId);
        return creatives.stream()
                .map(c -> variantPerformance(c, from, to))
                .filter(p -> p.posts() > 0)
                .sorted(Comparator.comparingLong(VariantPerformance::views).reversed())
                .limit(limit)
                .toList();
    }

    /** Package-visible so {@link CreativeExperimentService} can resolve a family's root the same way. */
    Creative rootOf(String projectId, Creative creative) {
        return creative.getParentCreativeId() != null ? findCreative(projectId, creative.getParentCreativeId()) : creative;
    }

    private VariantPerformance variantPerformance(Creative creative, OffsetDateTime from, OffsetDateTime to) {
        List<PostPublishTarget> targets = attributionResolver.resolvePublishedTargets(creative.getId());
        if (from != null) {
            targets = targets.stream()
                    .filter(t -> t.getFireTime() != null && !t.getFireTime().isBefore(from) && t.getFireTime().isBefore(to))
                    .toList();
        }
        if (targets.isEmpty()) {
            return empty(creative);
        }

        List<String> targetIds = targets.stream().map(PostPublishTarget::getId).toList();
        Map<String, PostPublishTargetMetric> latestByTarget = new LinkedHashMap<>();
        for (PostPublishTargetMetric m : metricRepository.findLatestAvailableForTargets(targetIds)) {
            latestByTarget.put(m.getTargetId(), m);
        }

        int posts = 0;
        long views = 0;
        long engagementNumerator = 0;
        List<Double> avgViewPcts = new ArrayList<>();
        Map<String, long[]> byPlatform = new LinkedHashMap<>();
        long views72hSum = 0;
        boolean any72h = false;

        for (PostPublishTarget target : targets) {
            PostPublishTargetMetric latest = latestByTarget.get(target.getId());
            if (latest == null) {
                continue;
            }
            posts++;
            long v = orZero(latest.getViews());
            long engagement = orZero(latest.getLikes()) + orZero(latest.getComments())
                    + orZero(latest.getShares()) + orZero(latest.getSaves());
            views += v;
            engagementNumerator += engagement;
            Double avgPct = avgViewPct(latest.getExtra());
            if (avgPct != null) {
                avgViewPcts.add(avgPct);
            }

            long[] bucket = byPlatform.computeIfAbsent(target.getPlatform(), k -> new long[3]);
            bucket[0] += 1;
            bucket[1] += v;
            bucket[2] += engagement;

            if (target.getFireTime() != null) {
                OffsetDateTime windowAt = target.getFireTime().plusHours(VIEWS_72H_HOURS);
                Optional<PostPublishTargetMetric> at72h = metricRepository.findFirstAvailableAtOrAfter(target.getId(), windowAt);
                if (at72h.isPresent()) {
                    views72hSum += orZero(at72h.get().getViews());
                    any72h = true;
                }
            }
        }

        if (posts == 0) {
            return empty(creative);
        }

        Double engagementRate = views > 0 ? (double) engagementNumerator / views : null;
        Double avgViewPct = avgViewPcts.isEmpty() ? null
                : avgViewPcts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        Long views72h = any72h ? views72hSum : null;

        List<PlatformBreakdown> platformBreakdown = byPlatform.entrySet().stream()
                .map(e -> new PlatformBreakdown(e.getKey(), (int) e.getValue()[0], e.getValue()[1],
                        e.getValue()[1] > 0 ? (double) e.getValue()[2] / e.getValue()[1] : null))
                .sorted(Comparator.comparingLong(PlatformBreakdown::views).reversed())
                .toList();

        return new VariantPerformance(creative.getId(), creative.displayId(), creative.getHeadline(), posts, views,
                engagementRate, avgViewPct, views72h, platformBreakdown);
    }

    private VariantPerformance empty(Creative creative) {
        return new VariantPerformance(creative.getId(), creative.displayId(), creative.getHeadline(), 0, 0,
                null, null, null, List.of());
    }

    private static Double avgViewPct(JsonNode extra) {
        if (extra == null) {
            return null;
        }
        JsonNode node = extra.get("avg_view_pct");
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    Creative findCreative(String projectId, String creativeId) {
        return creativeRepository.findByIdAndProjectId(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
    }

    private void requireMember(String projectId, User caller) {
        if (caller == null || !projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }
}
