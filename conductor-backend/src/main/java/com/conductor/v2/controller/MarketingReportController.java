package com.conductor.v2.controller;

import com.conductor.generated.v2.api.MarketingReportApi;
import com.conductor.generated.v2.model.MarketingBenchmark;
import com.conductor.generated.v2.model.MarketingBenchmarkListResponse;
import com.conductor.generated.v2.model.MarketingBenchmarkRequest;
import com.conductor.generated.v2.model.MarketingReport;
import com.conductor.generated.v2.model.OverviewMetricDefinition;
import com.conductor.generated.v2.model.ReportAcquisitionRow;
import com.conductor.generated.v2.model.ReportAd;
import com.conductor.generated.v2.model.ReportAdsCoverage;
import com.conductor.generated.v2.model.ReportBenchmark;
import com.conductor.generated.v2.model.ReportChange;
import com.conductor.generated.v2.model.ReportChangeMarker;
import com.conductor.generated.v2.model.ReportChanges;
import com.conductor.generated.v2.model.ReportCoverage;
import com.conductor.generated.v2.model.ReportDay;
import com.conductor.generated.v2.model.ReportDerivation;
import com.conductor.generated.v2.model.ReportFunnel;
import com.conductor.generated.v2.model.ReportFunnelStage;
import com.conductor.generated.v2.model.ReportKeyword;
import com.conductor.generated.v2.model.ReportMetric;
import com.conductor.generated.v2.model.ReportScorecardRow;
import com.conductor.generated.v2.model.ReportScorecardSource;
import com.conductor.generated.v2.model.ReportSource;
import com.conductor.generated.v2.model.ReportStore;
import com.conductor.generated.v2.model.ReportWindow;
import com.conductor.generated.v2.model.WorkItemAssignee;
import com.conductor.marketing.overview.MetricCatalog;
import com.conductor.marketing.report.MarketingBenchmarkService;
import com.conductor.marketing.report.MarketingReportService;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one-call marketing report and the industry benchmarks it compares against. Any project member reads;
 * writing a benchmark needs ADMIN or CREATOR. The {@code /api/v2} prefix is applied structurally by
 * {@code ApiPathConfig}.
 */
@RestController
public class MarketingReportController implements MarketingReportApi {

    private final MarketingReportService reportService;
    private final MarketingBenchmarkService benchmarkService;
    private final ProjectSecurityService projectSecurityService;

    public MarketingReportController(MarketingReportService reportService, MarketingBenchmarkService benchmarkService,
                                     ProjectSecurityService projectSecurityService) {
        this.reportService = reportService;
        this.benchmarkService = benchmarkService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<MarketingReport> getMarketingReport(String projectId, String window) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(toReport(reportService.report(projectId, window)));
    }

    @Override
    public ResponseEntity<MarketingBenchmarkListResponse> listMarketingBenchmarks(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(new MarketingBenchmarkListResponse(
                benchmarkService.list(projectId).stream().map(MarketingReportController::toBenchmark).toList()));
    }

    @Override
    public ResponseEntity<MarketingBenchmark> setMarketingBenchmark(String projectId, String metricRef,
                                                                    MarketingBenchmarkRequest request) {
        var actor = projectSecurityService.requireProjectEditor(projectId);
        var saved = benchmarkService.upsert(projectId, metricRef,
                new MarketingBenchmarkService.Input(request.getLabel(), request.getValue(), request.getRangeText(),
                        request.getSegment(), request.getSourceName(), request.getSourceUrl(), request.getCheckedOn(),
                        request.getNotes(), request.getNumerator(), request.getDenominator(), request.getSourceType()),
                actor.userId());
        return ResponseEntity.ok(toBenchmark(saved));
    }

    @Override
    public ResponseEntity<Void> deleteMarketingBenchmark(String projectId, String metricRef) {
        projectSecurityService.requireProjectEditor(projectId);
        benchmarkService.delete(projectId, metricRef);
        return ResponseEntity.noContent().build();
    }

    // ---- mapping ------------------------------------------------------------------------------------

    private static MarketingBenchmark toBenchmark(com.conductor.marketing.report.MarketingBenchmark b) {
        return new MarketingBenchmark(b.getMetricRef(), b.getValue().doubleValue(), b.getSourceName(),
                b.getSourceUrl(), b.getCheckedOn(), b.getUpdatedAt())
                .label(b.getLabel()).rangeText(b.getRangeText()).segment(b.getSegment()).notes(b.getNotes())
                .numerator(b.getNumerator()).denominator(b.getDenominator()).sourceType(b.getSourceType());
    }

    static MarketingReport toReport(MarketingReportService.Report r) {
        return new MarketingReport(
                new ReportWindow(r.window().from()).to(r.window().to()),
                r.generatedAt(),
                r.sources().stream().map(s -> new ReportSource(s.channel(), s.status()).connector(s.connector())
                        .dataCompleteThrough(s.dataCompleteThrough()).lastFetchedAt(s.lastFetchedAt())
                        .timeZone(s.timeZone()).searchTermTimeZone(s.searchTermTimeZone())).toList(),
                r.headline().stream().map(MarketingReportController::toMetric).toList(),
                r.acquisition().stream().map(MarketingReportController::toAcquisitionRow).toList(),
                new ReportFunnel(ReportFunnel.BasisEnum.valueOf(r.funnel().basis()),
                        r.funnel().stages().stream().map(s -> new ReportFunnelStage(s.key(), s.label(), s.biggestDrop())
                                .value(s.value()).dropPct(s.dropPct()).coverage(toCoverage(s.coverage()))
                                .note(s.note())).toList()),
                r.daily().stream().map(d -> new ReportDay(d.day(), d.provisional())
                        .storeImpressions(d.storeImpressions()).adSpend(d.adSpend())).toList(),
                r.scorecard().stream().map(s -> new ReportScorecardRow(s.key(), s.label(), s.status(),
                        new ReportScorecardSource(s.source().channel()).connector(s.source().connector()))
                        .area(s.area()).value(s.value()).target(s.target()).targetWindow(s.targetWindow())
                        .missPct(s.missPct()).basis(s.basis()).sampleSize(s.sampleSize())
                        .definition(MarketingOverviewController.toDefinition(s.definition()))).toList(),
                r.keywords().stream().map(k -> new ReportKeyword(k.keywordId(), k.text(), k.judgeable())
                        .adGroup(k.adGroup()).campaign(k.campaign()).status(k.status()).matchType(k.matchType())
                        .bid(k.bid()).impressions(k.impressions()).taps(k.taps()).tapRate(k.tapRate())
                        .installs(k.installs()).installRate(k.installRate()).spend(k.spend()).cpi(k.cpi())
                        .trials(k.trials()).activations(k.activations()).newDownloads(k.newDownloads())).toList(),
                r.ads().stream().map(a -> new ReportAd(a.adId(), a.name()).adGroup(a.adGroup())
                        .creativeType(a.creativeType()).productPageId(a.productPageId()).status(a.status())
                        .impressions(a.impressions()).taps(a.taps()).installs(a.installs())
                        .installRate(a.installRate()).spend(a.spend()).cpi(a.cpi())).toList(),
                r.benchmarks().stream().map(MarketingReportController::toReportBenchmark).toList(),
                new ReportChanges(changes(r.changes().open()), changes(r.changes().dueForReview()),
                        changes(r.changes().reviewedInWindow())))
                .adsCoverage(r.adsCoverage().stream().map(c -> new ReportAdsCoverage(c.campaignId(), c.name(), c.rows())
                        .spend(c.spend()).keywordSpend(c.keywordSpend()).searchTermSpend(c.searchTermSpend())
                        .keywordCoveragePct(c.keywordCoveragePct())).toList())
                .changeMarkers(r.changeMarkers().stream().map(m -> new ReportChangeMarker(m.day(), m.changeId(),
                        m.displayId(), m.title(), m.status())).toList())
                .definitions(toDefinitions(r.definitions()))
                .store(r.store() == null ? null : new ReportStore().downloadsOfRecord(r.store().downloadsOfRecord())
                        .analyticsDownloads(r.store().analyticsDownloads())
                        .coverage(toCoverage(r.store().coverage())));
    }

    private static ReportAcquisitionRow toAcquisitionRow(MarketingReportService.AcquisitionRow a) {
        return new ReportAcquisitionRow(a.key(), a.label(), a.status(), a.derived())
                .reach(a.reach()).reachLabel(a.reachLabel()).actions(a.actions())
                .actionsLabel(a.actionsLabel()).installs(a.installs()).cost(a.cost()).note(a.note())
                .viewThroughInstalls(a.viewThroughInstalls()).likes(a.likes())
                .derivation(a.derivation() == null ? null
                        : new ReportDerivation(a.derivation().from(), a.derivation().to(), a.derivation().days())
                                .grossInstalls(a.derivation().grossInstalls())
                                .paidInstalls(a.derivation().paidInstalls())
                                .grossReach(a.derivation().grossReach()).paidReach(a.derivation().paidReach()));
    }

    private static ReportBenchmark toReportBenchmark(MarketingReportService.Benchmark b) {
        return new ReportBenchmark(b.metricRef(), b.value(), b.benchmark(), b.sourceName(), b.sourceUrl(),
                b.checkedOn(), b.judged(), b.stale())
                .label(b.label()).rangeText(b.rangeText()).segment(b.segment()).ratio(b.ratio())
                .goodDirection(b.goodDirection() == null ? null
                        : ReportBenchmark.GoodDirectionEnum.valueOf(b.goodDirection()))
                .sampleSize(b.sampleSize()).numerator(b.numerator()).denominator(b.denominator())
                .sourceType(b.sourceType()).comparedMetricRef(b.comparedMetricRef())
                .metricDefinition(MarketingOverviewController.toDefinition(b.metricDefinition()))
                .comparable(b.comparable()).reason(b.reason());
    }

    private static ReportCoverage toCoverage(MarketingReportService.Coverage c) {
        return c == null ? null : new ReportCoverage(c.daysCovered(), c.daysInWindow());
    }

    private static Map<String, OverviewMetricDefinition> toDefinitions(
            Map<String, MetricCatalog.Definition> definitions) {
        Map<String, OverviewMetricDefinition> out = new LinkedHashMap<>();
        definitions.forEach((ref, d) -> out.put(ref, MarketingOverviewController.toDefinition(d)));
        return out;
    }

    private static ReportMetric toMetric(MarketingReportService.Metric m) {
        return new ReportMetric(m.channel(), m.key(), m.label(), m.unit(), m.status(),
                ReportMetric.GoodDirectionEnum.valueOf(m.goodDirection()))
                .value(m.value()).previous(m.previous()).target(m.target()).missPct(m.missPct())
                .sampleSize(m.sampleSize()).definition(MarketingOverviewController.toDefinition(m.definition()));
    }

    private static List<ReportChange> changes(List<MarketingReportService.Change> items) {
        return items.stream().map(c -> new ReportChange(c.id(), c.displayId(), c.title(), c.type(), c.status())
                .assignee(c.assignee() == null ? null
                        : new WorkItemAssignee(c.assignee().userId(), c.assignee().name())
                                .avatarUrl(c.assignee().avatarUrl()))
                .dueOn(c.dueOn()).fields(c.fields())).toList();
    }
}
