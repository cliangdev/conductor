package com.conductor.v2.controller;

import com.conductor.generated.v2.api.MarketingOverviewApi;
import com.conductor.generated.v2.model.MarketingChannelDetailResponse;
import com.conductor.generated.v2.model.MarketingOverviewResponse;
import com.conductor.generated.v2.model.MarketingTarget;
import com.conductor.generated.v2.model.MarketingTargetRequest;
import com.conductor.generated.v2.model.OverviewChannel;
import com.conductor.generated.v2.model.OverviewConnection;
import com.conductor.generated.v2.model.OverviewConnectHint;
import com.conductor.generated.v2.model.OverviewDimension;
import com.conductor.generated.v2.model.OverviewDimensionColumn;
import com.conductor.generated.v2.model.OverviewDimensionRow;
import com.conductor.generated.v2.model.OverviewFunnelStage;
import com.conductor.generated.v2.model.OverviewMetric;
import com.conductor.generated.v2.model.OverviewSeriesPoint;
import com.conductor.generated.v2.model.OverviewWindow;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The marketing overview: daily channel metrics, the funnel and per-metric targets. Any project member
 * reads; setting or clearing a target needs ADMIN or CREATOR. The {@code /api/v2} prefix is applied
 * structurally by {@code ApiPathConfig}, so the mappings are bare.
 */
@RestController
public class MarketingOverviewController implements MarketingOverviewApi {

    private final MarketingOverviewQueryService queryService;
    private final ProjectSecurityService projectSecurityService;

    public MarketingOverviewController(MarketingOverviewQueryService queryService,
                                       ProjectSecurityService projectSecurityService) {
        this.queryService = queryService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<MarketingOverviewResponse> getMarketingOverview(String projectId, String window) {
        projectSecurityService.requireProjectAccess(projectId);
        MarketingOverviewQueryService.Overview overview = queryService.overview(projectId, window);
        return ResponseEntity.ok(new MarketingOverviewResponse(toWindow(overview.window()),
                overview.funnel().stream().map(MarketingOverviewController::toStage).toList(),
                overview.channels().stream().map(MarketingOverviewController::toChannel).toList()));
    }

    @Override
    public ResponseEntity<MarketingChannelDetailResponse> getMarketingChannel(String projectId, String channel,
                                                                              String window) {
        projectSecurityService.requireProjectAccess(projectId);
        MarketingOverviewQueryService.ChannelDetail detail = queryService.channel(projectId, channel, window);
        MarketingChannelDetailResponse response = new MarketingChannelDetailResponse(toWindow(detail.window()),
                toChannel(detail.channel()),
                detail.dimensions().stream().map(MarketingOverviewController::toDimension).toList());
        if (!detail.topPosts().isEmpty()) {
            response.topPosts(detail.topPosts().stream().map(PublishMetricsController::toInsightsPost).toList());
        }
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<MarketingTarget> setMarketingTarget(String projectId, String channel, String metric,
                                                              MarketingTargetRequest request) {
        var actor = projectSecurityService.requireProjectEditor(projectId);
        MarketingOverviewQueryService.TargetView target = queryService.setTarget(projectId, channel, metric,
                request.getTarget(), request.getDirection() == null ? null : request.getDirection().name(),
                actor.userId());
        return ResponseEntity.ok(toTarget(target));
    }

    @Override
    public ResponseEntity<Void> deleteMarketingTarget(String projectId, String channel, String metric) {
        projectSecurityService.requireProjectEditor(projectId);
        queryService.clearTarget(projectId, channel, metric);
        return ResponseEntity.noContent().build();
    }

    // ---- mapping ------------------------------------------------------------------------------------

    private static OverviewWindow toWindow(MarketingOverviewQueryService.Window w) {
        return new OverviewWindow(w.from(), w.to(), w.previousFrom(), w.previousTo());
    }

    private static OverviewFunnelStage toStage(MarketingOverviewQueryService.FunnelStageView s) {
        return new OverviewFunnelStage(s.key(), s.label(), s.source(), s.available())
                .value(s.value())
                .previous(s.previous())
                .change(s.change())
                .unavailableReason(s.unavailableReason());
    }

    private static OverviewChannel toChannel(MarketingOverviewQueryService.ChannelView c) {
        OverviewChannel channel = new OverviewChannel(c.channel(), c.label(),
                OverviewChannel.StatusEnum.valueOf(c.status()),
                c.connections().stream()
                        .map(k -> new OverviewConnection(k.id(), k.connectorId(), k.name(),
                                OverviewConnection.StatusEnum.valueOf(k.status())))
                        .toList(),
                c.metrics().stream().map(MarketingOverviewController::toMetric).toList())
                .lastFetchedAt(c.lastFetchedAt());
        if (c.connectHintConnectorId() != null) {
            channel.connectHint(new OverviewConnectHint(c.connectHintConnectorId()));
        }
        return channel;
    }

    private static OverviewMetric toMetric(MarketingOverviewQueryService.MetricView m) {
        return new OverviewMetric(m.metric(), m.label(), OverviewMetric.UnitEnum.valueOf(m.unit().name()), m.noise(),
                m.provisionalDays(),
                m.series().stream().map(p -> new OverviewSeriesPoint(p.day()).value(p.value())).toList())
                .value(m.value())
                .previous(m.previous())
                .change(m.change())
                .changePct(m.changePct())
                .target(m.target() == null ? null : toTarget(m.target()));
    }

    private static MarketingTarget toTarget(MarketingOverviewQueryService.TargetView t) {
        return new MarketingTarget(t.value(), MarketingTarget.DirectionEnum.valueOf(t.direction()));
    }

    private static OverviewDimension toDimension(MarketingOverviewQueryService.DimensionTable d) {
        return new OverviewDimension(d.key(), d.label(),
                d.columns().stream()
                        .map(c -> new OverviewDimensionColumn(c.key(), c.label(),
                                OverviewDimensionColumn.UnitEnum.valueOf(c.unit().name())))
                        .toList(),
                d.rows().stream().map(r -> new OverviewDimensionRow(r.key(), r.label(), r.values())).toList());
    }
}
