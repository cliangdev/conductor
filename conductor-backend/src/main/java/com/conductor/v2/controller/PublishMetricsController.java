package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.generated.v2.api.PublishMetricsApi;
import com.conductor.generated.v2.model.PublishMetricSnapshot;
import com.conductor.generated.v2.model.PublishMetricsResponse;
import com.conductor.generated.v2.model.PublishMetricsTarget;
import com.conductor.generated.v2.model.TopPostEntry;
import com.conductor.service.publish.PublishMetricsQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * What happened to a post after it went out: the per-destination series the {@code post_metrics} feeds
 * filed, and the project's ranking by one metric. Reads only; the feeds do the writing. The {@code /api/v2}
 * prefix is applied structurally by {@code ApiPathConfig}, so the mappings are bare.
 */
@RestController
public class PublishMetricsController implements PublishMetricsApi {

    private final PublishMetricsQueryService queryService;

    public PublishMetricsController(PublishMetricsQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public ResponseEntity<PublishMetricsResponse> getWorkItemPublishMetrics(String projectId, String workItemId,
                                                                            OffsetDateTime since) {
        PublishMetricsQueryService.PostMetrics metrics = queryService.forPost(projectId, workItemId, since, currentUser());
        List<PublishMetricsTarget> targets = metrics.targets().stream()
                .map(t -> new PublishMetricsTarget(t.targetId(), t.platform(), toSnapshot(t.latest()),
                        t.series().stream().map(PublishMetricsController::toSnapshot).toList())
                        .accountLabel(t.accountLabel())
                        .permalink(t.permalink()))
                .toList();
        return ResponseEntity.ok(new PublishMetricsResponse(metrics.workItemId(), targets)
                .totals(metrics.totals() == null ? null : toSnapshot(metrics.totals())));
    }

    @Override
    public ResponseEntity<List<TopPostEntry>> listTopPosts(String projectId, String metric, String platform,
                                                           OffsetDateTime since, Integer limit) {
        List<PublishMetricsQueryService.TopPost> rows;
        try {
            rows = queryService.topPosts(projectId, metric, platform, since, limit == null ? 20 : limit, currentUser());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        return ResponseEntity.ok(rows.stream()
                .map(r -> new TopPostEntry(r.workItemId(), r.targetId(), r.platform(), r.metric(), r.value(), r.observedAt())
                        .displayId(r.displayId())
                        .title(r.title())
                        .accountLabel(r.accountLabel())
                        .permalink(r.permalink()))
                .toList());
    }

    private static PublishMetricSnapshot toSnapshot(PublishMetricsQueryService.Snapshot s) {
        return new PublishMetricSnapshot(s.observedAt(), s.unavailable())
                .views(s.views()).likes(s.likes()).comments(s.comments()).shares(s.shares()).saves(s.saves())
                .reach(s.reach()).impressions(s.impressions()).watchTimeSeconds(s.watchTimeSeconds());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
