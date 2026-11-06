package com.conductor.v2.controller;

import com.conductor.generated.v2.api.MarketingBudgetApi;
import com.conductor.generated.v2.model.BudgetCapRequest;
import com.conductor.generated.v2.model.BudgetChannel;
import com.conductor.generated.v2.model.BudgetCpiWindow;
import com.conductor.generated.v2.model.BudgetDay;
import com.conductor.generated.v2.model.BudgetMarginalCpi;
import com.conductor.generated.v2.model.BudgetPacing;
import com.conductor.generated.v2.model.BudgetRuleEvaluation;
import com.conductor.generated.v2.model.MarketingBudgetCap;
import com.conductor.generated.v2.model.StopRule;
import com.conductor.generated.v2.model.StopRuleListResponse;
import com.conductor.generated.v2.model.StopRuleRequest;
import com.conductor.marketing.budget.BudgetPacingService;
import com.conductor.marketing.budget.BudgetPacingService.StopRuleInput;
import com.conductor.marketing.budget.MarketingBudget;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The monthly paid budget, its pacing across paid channels, and the stop rules that guard each channel. Any
 * project member reads; writing needs ADMIN or CREATOR. Conductor only records and evaluates: nothing here
 * changes a bid or budget on an ad platform. The {@code /api/v2} prefix is applied structurally by
 * {@code ApiPathConfig}.
 */
@RestController
public class MarketingBudgetController implements MarketingBudgetApi {

    private final BudgetPacingService service;
    private final ProjectSecurityService projectSecurityService;

    public MarketingBudgetController(BudgetPacingService service, ProjectSecurityService projectSecurityService) {
        this.service = service;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<BudgetPacing> getMarketingBudget(String projectId, String month) {
        projectSecurityService.requireProjectAccess(projectId);
        var pacing = service.pacing(projectId, BudgetPacingService.parseMonth(month), LocalDate.now(ZoneOffset.UTC));
        return ResponseEntity.ok(toPacing(pacing));
    }

    @Override
    public ResponseEntity<MarketingBudgetCap> setMarketingBudget(String projectId, String month,
                                                                 BudgetCapRequest request) {
        var actor = projectSecurityService.requireProjectEditor(projectId);
        BigDecimal cap = BigDecimal.valueOf(request.getCapAmount());
        MarketingBudget saved = service.setBudget(projectId, month, cap, request.getCurrency(), actor.userId());
        return ResponseEntity.ok(new MarketingBudgetCap(saved.getMonth(), saved.getCapAmount().doubleValue(),
                saved.getCurrency(), saved.getUpdatedAt()));
    }

    @Override
    public ResponseEntity<StopRuleListResponse> listMarketingStopRules(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(new StopRuleListResponse(
                service.listStopRules(projectId).stream().map(MarketingBudgetController::toStopRule).toList()));
    }

    @Override
    public ResponseEntity<StopRule> createMarketingStopRule(String projectId, StopRuleRequest request) {
        var actor = projectSecurityService.requireProjectEditor(projectId);
        var saved = service.addStopRule(projectId,
                new StopRuleInput(request.getChannel(), request.getScope(), request.getMetric(),
                        request.getComparator(), BigDecimal.valueOf(request.getThreshold()), request.getMinSample(),
                        request.getWindowDays(), request.getNote()),
                actor.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toStopRule(saved));
    }

    @Override
    public ResponseEntity<Void> deleteMarketingStopRule(String projectId, String ruleId) {
        projectSecurityService.requireProjectEditor(projectId);
        service.removeStopRule(projectId, ruleId);
        return ResponseEntity.noContent().build();
    }

    // ---- mapping ------------------------------------------------------------------------------------

    private static StopRule toStopRule(com.conductor.marketing.budget.MarketingStopRule r) {
        return new StopRule(r.getId(), r.getChannel(), r.getScope(), r.getMetric(), r.getComparator(),
                r.getThreshold().doubleValue(), r.getMinSample(), r.getWindowDays(), r.getCreatedAt())
                .note(r.getNote());
    }

    static BudgetPacing toPacing(BudgetPacingService.Pacing p) {
        return new BudgetPacing(p.month(), p.daysElapsed(), p.daysInMonth(), p.mtdSpend(), p.provisionalSpend(),
                toDays(p.daily()), p.channels().stream().map(MarketingBudgetController::toChannel).toList())
                .capAmount(p.capAmount()).currency(p.currency()).through(p.through())
                .expectedSpend(p.expectedSpend()).pacePct(p.pacePct()).projectedSpend(p.projectedSpend());
    }

    private static List<BudgetDay> toDays(List<BudgetPacingService.DayPoint> days) {
        return days.stream().map(d -> new BudgetDay(d.day(), d.cumulativeSpend()).spend(d.spend())
                .provisionalSpend(d.provisionalSpend()).expectedSpend(d.expectedSpend())).toList();
    }

    private static BudgetChannel toChannel(BudgetPacingService.ChannelPacing c) {
        return new BudgetChannel(c.channel(), c.mtdSpend(), c.provisionalSpend(), c.compliant(), toDays(c.daily()),
                c.rules().stream().map(MarketingBudgetController::toEvaluation).toList())
                .through(c.through()).installs(c.installs()).cpi(c.cpi()).complianceReason(c.complianceReason())
                .marginalCpi(c.marginalCpi() == null ? null : new BudgetMarginalCpi(toWindow(c.marginalCpi().last7()),
                        toWindow(c.marginalCpi().prior7()), toWindow(c.marginalCpi().monthToDate())));
    }

    private static BudgetCpiWindow toWindow(BudgetPacingService.CpiWindow w) {
        return new BudgetCpiWindow(w.from()).to(w.to()).spend(w.spend()).installs(w.installs()).cpi(w.cpi());
    }

    private static BudgetRuleEvaluation toEvaluation(BudgetPacingService.RuleEvaluation e) {
        return new BudgetRuleEvaluation(e.ruleId(), e.channel(), e.scope(), e.metric(), e.comparator(),
                e.threshold().doubleValue(), e.minSample(), e.windowDays(), e.tripped(), e.belowMinSample())
                .note(e.note()).windowFrom(e.windowFrom()).windowTo(e.windowTo()).observed(e.observed())
                .sample(e.sample()).detail(e.detail());
    }
}
