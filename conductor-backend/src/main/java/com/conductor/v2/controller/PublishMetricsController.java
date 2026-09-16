package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.generated.v2.api.PublishMetricsApi;
import com.conductor.generated.v2.model.InsightsCoverage;
import com.conductor.generated.v2.model.InsightsGroup;
import com.conductor.generated.v2.model.InsightsMover;
import com.conductor.generated.v2.model.InsightsPost;
import com.conductor.generated.v2.model.InsightsWindow;
import com.conductor.generated.v2.model.MarketingInsightsResponse;
import com.conductor.generated.v2.model.PublishMetricSnapshot;
import com.conductor.generated.v2.model.PublishMetricsResponse;
import com.conductor.generated.v2.model.PublishMetricsTarget;
import com.conductor.generated.v2.model.TopPostEntry;
import com.conductor.marketing.insights.MarketingInsightsQueryService;
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
    private final MarketingInsightsQueryService insightsQueryService;

    public PublishMetricsController(PublishMetricsQueryService queryService,
                                    MarketingInsightsQueryService insightsQueryService) {
        this.queryService = queryService;
        this.insightsQueryService = insightsQueryService;
    }

    @Override
    public ResponseEntity<MarketingInsightsResponse> getMarketingInsights(String projectId, String window,
                                                                          String platform) {
        MarketingInsightsQueryService.Insights insights = insightsQueryService.compute(projectId, window, platform,
                currentUser());
        return ResponseEntity.ok(toResponse(insights));
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

    private static MarketingInsightsResponse toResponse(MarketingInsightsQueryService.Insights r) {
        return new MarketingInsightsResponse(toWindow(r.window()), toGroup(r.totals()),
                r.byPlatform().stream().map(PublishMetricsController::toGroup).toList(),
                r.byFormat().stream().map(PublishMetricsController::toGroup).toList(),
                r.byHour().stream().map(PublishMetricsController::toGroup).toList(),
                r.byWeekday().stream().map(PublishMetricsController::toGroup).toList(),
                r.topPosts().stream().map(PublishMetricsController::toInsightsPost).toList(),
                r.bottomPosts().stream().map(PublishMetricsController::toInsightsPost).toList(),
                r.movers().stream().map(PublishMetricsController::toMover).toList(),
                new InsightsCoverage(r.coverage().platforms(), r.coverage().notes()));
    }

    private static InsightsWindow toWindow(MarketingInsightsQueryService.Window w) {
        return new InsightsWindow(w.from(), w.to(), w.days());
    }

    private static InsightsGroup toGroup(MarketingInsightsQueryService.Group g) {
        return new InsightsGroup(g.key(), g.posts(), g.views(), g.likes(), g.comments(), g.shares(), g.saves())
                .label(g.label())
                .engagementRate(g.engagementRate())
                .medianViews(g.medianViews())
                .avgViewPct(g.avgViewPct());
    }

    private static InsightsPost toInsightsPost(MarketingInsightsQueryService.Post p) {
        return new InsightsPost(p.workItemId(), p.targetId(), p.platform(), p.views(), p.observedAt())
                .displayId(p.displayId())
                .title(p.title())
                .workflowSlug(p.workflowSlug())
                .captionExcerpt(p.captionExcerpt())
                .format(p.format())
                .accountLabel(p.accountLabel())
                .permalink(p.permalink())
                .firedAt(p.firedAt())
                .likes(p.likes())
                .comments(p.comments())
                .shares(p.shares())
                .saves(p.saves())
                .engagementRate(p.engagementRate())
                .avgViewPct(p.avgViewPct());
    }

    private static InsightsMover toMover(MarketingInsightsQueryService.Mover m) {
        return new InsightsMover(m.metric(), m.current(), m.previous()).deltaPct(m.deltaPct());
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
