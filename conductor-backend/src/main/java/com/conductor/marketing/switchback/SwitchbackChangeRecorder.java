package com.conductor.marketing.switchback;

import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.service.ProjectActor;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartField;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a settled switchback test into the project's {@code MARKETING_CHANGES} workflow as one REVIEWED change of type
 * ADS: the rule as the hypothesis, the off-week means as the baseline, the per-week table as the outcome, then the verdict
 * and one sentence of lesson.
 *
 * <p>Created the way the change-log import creates a change that already happened: {@link WorkItemService#createWorkItem}
 * (so the sequence number, pinned workflow version and field checks are the service's own), then the status is set on the
 * created entity. That skips the "Approve verdict" review gate on purpose: the figures are settled data, there is nothing
 * for a person to approve, and it must not notify anyone for a result nobody asked about. A person can still read and
 * discuss the change like any other.
 *
 * <p>Call this inside the settling transaction: the change and the test's link to it must commit together, and a workflow
 * that cannot hold the change (no {@code MARKETING_CHANGES}, no REVIEWED status or verdict option) must roll the settle back.
 */
@Component
class SwitchbackChangeRecorder {

    static final String WORKFLOW = "MARKETING_CHANGES";
    static final String TYPE = "ADS";
    static final String STATUS_REVIEWED = "REVIEWED";
    static final String CONTROL = "alternate weeks off";
    private static final String ACTOR_LABEL = "Switchback test";

    private final WorkItemService workItemService;
    private final WorkItemWorkflowService workflowService;

    SwitchbackChangeRecorder(WorkItemService workItemService, WorkItemWorkflowService workflowService) {
        this.workItemService = workItemService;
        this.workflowService = workflowService;
    }

    WorkItem record(MarketingSwitchbackTest test, SwitchbackRule rule, List<SwitchbackWeek> weeks,
                    SwitchbackOutcome outcome, LocalDate today) {
        String projectId = test.getProjectId();
        Statechart chart = workflowService.statechartFor(projectId, WORKFLOW);
        requireReviewedStatus(chart);
        requireVerdictOption(chart, outcome.verdict());

        LocalDate firstWeek = weeks.get(0).weekStart();
        LocalDate lastDay = weeks.get(weeks.size() - 1).weekEnd();
        long onWeeks = weeks.stream().filter(SwitchbackWeek::on).count();

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hypothesis", rule.incrementalIf());
        fields.put("baseline", SwitchbackEvaluator.baselineText(outcome));
        fields.put("control", CONTROL);
        fields.put("liveSince", firstWeek.toString());
        fields.put("outcome", SwitchbackEvaluator.outcomeText(outcome));
        fields.put("verdict", outcome.verdict());
        fields.put("lesson", outcome.reason());
        fields.put("evidence", "Switchback test " + test.getId() + ", settled " + today + ". App Store Connect "
                + "downloads at " + SwitchbackDataReader.SEARCH_SOURCE + " and Apple Ads new_downloads, settled UTC "
                + "days " + firstWeek + " to " + lastDay + ".");

        String description = "Switchback test \"" + test.getName() + "\" of the Apple Ads keyword \""
                + test.getKeywordText() + "\" (id " + test.getKeywordId() + "): " + weeks.size() + " weeks from "
                + firstWeek + " to " + lastDay + ", the keyword on in " + onWeeks + " of them. Settled by Conductor "
                + "from daily data that was no longer provisional.";

        WorkItem change = workItemService.createWorkItem(projectId, TYPE, "Switchback: " + test.getKeywordText(),
                description, WORKFLOW, null, fields, today, ProjectActor.agent(ACTOR_LABEL));
        change.setCurrentStatus(STATUS_REVIEWED);
        change.setStartedAt(firstWeek.atStartOfDay().atOffset(ZoneOffset.UTC));
        change.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return change;
    }

    private static void requireReviewedStatus(Statechart chart) {
        if (!chart.hasStatus(STATUS_REVIEWED)) {
            throw new BusinessException("The " + WORKFLOW + " workflow has no status " + STATUS_REVIEWED);
        }
    }

    private static void requireVerdictOption(Statechart chart, String verdict) {
        boolean known = chart.field("verdict").map(StatechartField::options).map(o -> o.contains(verdict)).orElse(false);
        if (!known) {
            throw new BusinessException("The " + WORKFLOW + " workflow has no verdict option " + verdict);
        }
    }
}
