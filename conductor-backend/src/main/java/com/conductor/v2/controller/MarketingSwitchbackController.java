package com.conductor.v2.controller;

import com.conductor.generated.v2.api.MarketingSwitchbackApi;
import com.conductor.generated.v2.model.CreateSwitchbackTestRequest;
import com.conductor.generated.v2.model.SwitchbackCurrentWeek;
import com.conductor.generated.v2.model.SwitchbackDecisionRule;
import com.conductor.generated.v2.model.SwitchbackDecisionRuleInput;
import com.conductor.generated.v2.model.SwitchbackPlanWeek;
import com.conductor.generated.v2.model.SwitchbackResult;
import com.conductor.generated.v2.model.SwitchbackResultMeans;
import com.conductor.generated.v2.model.SwitchbackResultWeek;
import com.conductor.generated.v2.model.SwitchbackTest;
import com.conductor.generated.v2.model.SwitchbackTestList;
import com.conductor.generated.v2.model.UpdateSwitchbackTestRequest;
import com.conductor.exception.BusinessException;
import com.conductor.marketing.switchback.SwitchbackOutcome;
import com.conductor.marketing.switchback.SwitchbackRule;
import com.conductor.marketing.switchback.SwitchbackTestService;
import com.conductor.marketing.switchback.SwitchbackTestService.Fields;
import com.conductor.marketing.switchback.SwitchbackTestService.View;
import com.conductor.marketing.switchback.SwitchbackWeek;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Switchback incrementality tests of one Apple Ads keyword. Any project member reads; writing needs ADMIN or CREATOR.
 * Conductor only declares, says which state each week should be in, and settles: a person applies the keyword switch.
 * The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig}.
 */
@RestController
public class MarketingSwitchbackController implements MarketingSwitchbackApi {

    private final SwitchbackTestService service;
    private final ProjectSecurityService projectSecurityService;

    public MarketingSwitchbackController(SwitchbackTestService service, ProjectSecurityService projectSecurityService) {
        this.service = service;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<SwitchbackTestList> listSwitchbackTests(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(new SwitchbackTestList(
                service.list(projectId).stream().map(MarketingSwitchbackController::toDto).toList()));
    }

    @Override
    public ResponseEntity<SwitchbackTest> createSwitchbackTest(String projectId, CreateSwitchbackTestRequest request) {
        var actor = projectSecurityService.requireProjectEditor(projectId);
        View view = service.create(projectId, toFields(request.getName(), request.getKeywordId(),
                request.getKeywordText(), request.getWeeks(), request.getMinN(), request.getDecisionRule()),
                actor.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(view));
    }

    @Override
    public ResponseEntity<SwitchbackTest> getSwitchbackTest(String projectId, String testId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(toDto(service.get(projectId, testId)));
    }

    @Override
    public ResponseEntity<SwitchbackTest> updateSwitchbackTest(String projectId, String testId,
                                                               UpdateSwitchbackTestRequest request) {
        projectSecurityService.requireProjectEditor(projectId);
        return ResponseEntity.ok(toDto(service.update(projectId, testId, toFields(request.getName(),
                request.getKeywordId(), request.getKeywordText(), request.getWeeks(), request.getMinN(),
                request.getDecisionRule()))));
    }

    @Override
    public ResponseEntity<SwitchbackTest> startSwitchbackTest(String projectId, String testId) {
        projectSecurityService.requireProjectEditor(projectId);
        return ResponseEntity.ok(toDto(service.start(projectId, testId)));
    }

    @Override
    public ResponseEntity<SwitchbackTest> cancelSwitchbackTest(String projectId, String testId) {
        projectSecurityService.requireProjectEditor(projectId);
        return ResponseEntity.ok(toDto(service.cancel(projectId, testId)));
    }

    // ---- mapping ------------------------------------------------------------------------------------

    private static Fields toFields(String name, String keywordId, String keywordText, List<SwitchbackPlanWeek> weeks,
                                   Integer minN, SwitchbackDecisionRuleInput rule) {
        return new Fields(name, keywordId, keywordText, weeks == null || weeks.isEmpty() ? null : weeks.stream().map(w -> {
            if (w == null || w.getOn() == null) {
                throw new BusinessException("Every week needs 'on' (true or false); an unknown state is not read as off");
            }
            return new SwitchbackWeek(w.getWeekStart(), w.getOn());
        }).toList(), minN, rule == null ? null : rule.getMetric(),
                rule == null || rule.getMinLift() == null ? null : BigDecimal.valueOf(rule.getMinLift()),
                rule == null || rule.getMaxCannibalisationPct() == null ? null
                        : BigDecimal.valueOf(rule.getMaxCannibalisationPct()));
    }

    private static SwitchbackTest toDto(View v) {
        SwitchbackTest dto = new SwitchbackTest(v.id(), v.name(), v.keywordId(), v.keywordText(),
                SwitchbackTest.StatusEnum.fromValue(v.status()),
                v.weeks().stream().map(w -> new SwitchbackPlanWeek(w.weekStart(), w.on())).toList(),
                toRule(v.rule()), v.minN(),
                new SwitchbackCurrentWeek(v.currentWeek().state()).weekStart(v.currentWeek().weekStart())
                        .weekEnd(v.currentWeek().weekEnd()),
                v.createdAt());
        return dto.verdict(v.verdict()).waitingFor(v.waitingFor())
                .result(v.result() == null ? null : toResult(v.result())).changeWorkItemId(v.changeWorkItemId())
                .startedAt(v.startedAt()).settledAt(v.settledAt());
    }

    private static SwitchbackDecisionRule toRule(SwitchbackRule r) {
        return new SwitchbackDecisionRule(r.metric(), r.minLift().doubleValue(), r.maxCannibalisationPct().doubleValue(),
                r.incrementalIf());
    }

    private static SwitchbackResult toResult(SwitchbackOutcome o) {
        return new SwitchbackResult(o.weeks().stream().map(w -> new SwitchbackResultWeek(
                java.time.LocalDate.parse(w.weekStart()), w.on(), w.searchDownloads().doubleValue(),
                w.adsNewDownloads().doubleValue(), w.organic().doubleValue(), w.totalInstalls().doubleValue())).toList(),
                means(o.onWeeks()), means(o.offWeeks()), o.n(), o.minN(), o.lift().doubleValue(), o.verdict(),
                o.reason()).organicChangePct(o.organicChangePct() == null ? null : o.organicChangePct().doubleValue());
    }

    private static SwitchbackResultMeans means(SwitchbackOutcome.Means m) {
        return new SwitchbackResultMeans(m.weeks(), m.meanOrganic().doubleValue(), m.meanTotalInstalls().doubleValue());
    }
}
