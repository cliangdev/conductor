package com.conductor.v2.controller;

import com.conductor.creative.CreativePerformanceService;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.generated.v2.api.PublishMetricsApi;
import com.conductor.generated.v2.model.CreativePerformanceEntry;
import com.conductor.generated.v2.model.CreativePerformancePlatform;
import com.conductor.generated.v2.model.InsightsCoverage;
import com.conductor.generated.v2.model.InsightsGroup;
import com.conductor.generated.v2.model.InsightsTagGroup;
import com.conductor.generated.v2.model.PlaybookEffect;
import com.conductor.generated.v2.model.PlaybookEffectSide;
import com.conductor.marketing.insights.PostBaselineService;
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
                                                                          String platform, String brandKitId,
                                                                          java.util.List<String> include) {
        MarketingInsightsQueryService.Insights insights = insightsQueryService.compute(projectId, window, platform,
                brandKitId, include != null && include.contains("tags"), currentUser());
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
        MarketingInsightsResponse response = new MarketingInsightsResponse(toWindow(r.window()), toGroup(r.totals()),
                r.byPlatform().stream().map(PublishMetricsController::toGroup).toList(),
                r.byFormat().stream().map(PublishMetricsController::toGroup).toList(),
                r.byHour().stream().map(PublishMetricsController::toGroup).toList(),
                r.byWeekday().stream().map(PublishMetricsController::toGroup).toList(),
                r.topPosts().stream().map(PublishMetricsController::toInsightsPost).toList(),
                r.bottomPosts().stream().map(PublishMetricsController::toInsightsPost).toList(),
                r.movers().stream().map(PublishMetricsController::toMover).toList(),
                new InsightsCoverage(r.coverage().platforms(), r.coverage().notes()),
                r.creatives().stream().map(PublishMetricsController::toCreativeEntry).toList());
        if (r.byTag() != null) {
            response.byTag(r.byTag().stream().map(PublishMetricsController::toTagGroup).toList())
                    .byTagWindow(toWindow(r.byTagWindow()))
                    .playbookEffect(new PlaybookEffect(new PlaybookEffectSide(r.playbookEffect().withLessons().posts())
                            .medianViewLift(r.playbookEffect().withLessons().medianViewLift()),
                            new PlaybookEffectSide(r.playbookEffect().withoutLessons().posts())
                                    .medianViewLift(r.playbookEffect().withoutLessons().medianViewLift())));
        }
        return response;
    }

    private static CreativePerformanceEntry toCreativeEntry(CreativePerformanceService.VariantPerformance p) {
        return new CreativePerformanceEntry(p.creativeId(), p.label(), p.posts(), p.views(),
                p.byPlatform().stream()
                        .map(b -> new CreativePerformancePlatform(b.platform(), b.posts(), b.views()).engagementRate(b.engagementRate()))
                        .toList())
                .headline(p.headline())
                .engagementRate(p.engagementRate())
                .avgViewPct(p.avgViewPct())
                .views72h(p.views72h())
                .settledPosts(p.settledPosts())
                .medianViewLift(p.medianViewLift())
                .medianRetentionLift(p.medianRetentionLift())
                .beatBaselineShare(p.beatBaselineShare())
                .withBaselinePosts(p.withBaselinePosts())
                .beatBaselinePosts(p.beatBaselinePosts())
                .viewVerdict(p.viewVerdict())
                .retentionVerdict(p.retentionVerdict());
    }

    private static InsightsWindow toWindow(MarketingInsightsQueryService.Window w) {
        return new InsightsWindow(w.from(), w.to(), w.days());
    }

    private static InsightsTagGroup toTagGroup(MarketingInsightsQueryService.TagGroup t) {
        MarketingInsightsQueryService.Group g = t.group();
        PostBaselineService.LiftSummary l = t.lift();
        return new InsightsTagGroup(g.key(), g.posts(), g.views(), g.likes(), g.comments(), g.shares(), g.saves(), t.tagKey())
                .label(g.label())
                .engagementRate(g.engagementRate())
                .medianViews(g.medianViews())
                .avgViewPct(g.avgViewPct())
                .unsettledPosts(t.unsettledPosts())
                .medianViewLift(l.medianViewLift())
                .beatBaselineShare(l.beatBaselineShare())
                .medianEngagementRate(l.medianEngagementRate())
                .medianRetentionLift(l.medianRetentionLift())
                .withBaselinePosts(l.withBaselinePosts())
                .beatBaselinePosts(l.beatBaselinePosts())
                .withRetentionBaselinePosts(l.withRetentionBaselinePosts())
                .beatRetentionBaselinePosts(l.beatRetentionBaselinePosts())
                .viewVerdict(l.viewVerdict())
                .retentionVerdict(l.retentionVerdict())
                .verdict(t.verdict())
                .verdictMetric(t.verdictMetric());
    }

    private static InsightsGroup toGroup(MarketingInsightsQueryService.Group g) {
        return new InsightsGroup(g.key(), g.posts(), g.views(), g.likes(), g.comments(), g.shares(), g.saves())
                .label(g.label())
                .engagementRate(g.engagementRate())
                .medianViews(g.medianViews())
                .avgViewPct(g.avgViewPct());
    }

    static InsightsPost toInsightsPost(MarketingInsightsQueryService.Post p) {
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
