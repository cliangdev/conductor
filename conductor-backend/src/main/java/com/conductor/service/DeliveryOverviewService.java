package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemAgentSession;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.MilestoneService.MilestoneView;
import com.conductor.service.ProgressRollup.Progress;
import com.conductor.service.ThroughputForecastService.Forecast;
import com.conductor.service.WorkItemHealthService.Health;
import com.conductor.service.WorkItemSignalService.Signals;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one-call delivery overview behind the Overview page: the PRDs in flight with their progress, health,
 * forecast and signals, the milestones, and what needs a person's attention. Everything is derived on read.
 *
 * <p>Reads: one load of the project's items, one signals pass, one health pass, one progress pass and one
 * milestone listing; a forecast per active item (bounded by the number of active PRDs).
 *
 * <p>The "delivery" items are the non-terminal ones that sit in a tree (have a parent or sub-items) or are
 * PRDs. Plain childless top-level items (Posts, standalone issues) are not part of delivery tracking and stay
 * out of the attention lists.
 */
@Service
public class DeliveryOverviewService {

    static final String PRD_TYPE = "PRD";

    private final WorkItemRepository workItemRepository;
    private final WorkItemHierarchyService hierarchyService;
    private final WorkItemSignalService signalService;
    private final WorkItemHealthService healthService;
    private final ThroughputForecastService forecastService;
    private final MilestoneService milestoneService;
    private final WorkItemWorkflowService workflowService;

    public DeliveryOverviewService(WorkItemRepository workItemRepository,
                                   WorkItemHierarchyService hierarchyService,
                                   WorkItemSignalService signalService, WorkItemHealthService healthService,
                                   ThroughputForecastService forecastService, MilestoneService milestoneService,
                                   WorkItemWorkflowService workflowService) {
        this.workItemRepository = workItemRepository;
        this.hierarchyService = hierarchyService;
        this.signalService = signalService;
        this.healthService = healthService;
        this.forecastService = forecastService;
        this.milestoneService = milestoneService;
        this.workflowService = workflowService;
    }

    /** An item with its status label, as the overview lists it. */
    public record ItemSummary(WorkItem item, String statusLabel) {
    }

    public record ActiveItem(ItemSummary item, Progress progress, Health health, Forecast forecast,
                             Signals signals) {
    }

    public record Attention(List<ItemSummary> blocked, List<ItemSummary> stale, List<ItemSummary> needsInput) {
    }

    public record Overview(List<ActiveItem> activeItems, List<MilestoneView> milestones, Attention attention) {
    }

    @Transactional(readOnly = true)
    public Overview overview(String projectId) {
        List<WorkItem> all = workItemRepository.findByProjectFiltered(projectId, null, null, null, null, null,
                false, null, null, null);
        Map<String, Integer> childCounts = new HashMap<>();
        for (WorkItem w : all) {
            if (w.getParentWorkItemId() != null) {
                childCounts.merge(w.getParentWorkItemId(), 1, Integer::sum);
            }
        }
        StatechartCache charts = new StatechartCache(workflowService, projectId);
        Comparator<WorkItem> order = Comparator
                .comparing((WorkItem w) -> w.getPriority() == null ? Integer.MAX_VALUE : w.getPriority())
                .thenComparing(WorkItem::getSequenceNumber);

        List<WorkItem> delivery = new ArrayList<>();
        List<WorkItem> active = new ArrayList<>();
        for (WorkItem w : all) {
            if (charts.isTerminal(w)) {
                continue;
            }
            boolean hasChildren = childCounts.getOrDefault(w.getId(), 0) > 0;
            boolean topLevel = w.getParentWorkItemId() == null;
            if (topLevel && (PRD_TYPE.equals(w.getType()) || hasChildren)) {
                active.add(w);
                delivery.add(w);
            } else if (!topLevel || hasChildren) {
                delivery.add(w);
            }
        }
        active.sort(order);
        delivery.sort(order);

        Map<String, Signals> signals = signalService.signalsFor(projectId, delivery);
        List<String> activeIds = active.stream().map(WorkItem::getId).toList();
        Map<String, Progress> progress = hierarchyService.progressFor(projectId, activeIds);
        Map<String, Forecast> forecasts = new LinkedHashMap<>();
        for (WorkItem w : active) {
            forecasts.put(w.getId(), forecastService.forecast(projectId, w));
        }
        Map<String, Health> health = healthService.current(projectId, active, forecasts);

        List<ActiveItem> activeItems = new ArrayList<>();
        for (WorkItem w : active) {
            activeItems.add(new ActiveItem(summary(charts, w), progress.getOrDefault(w.getId(), Progress.EMPTY),
                    health.get(w.getId()), forecasts.get(w.getId()), signals.get(w.getId())));
        }

        List<ItemSummary> blocked = new ArrayList<>();
        List<ItemSummary> stale = new ArrayList<>();
        List<ItemSummary> needsInput = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (WorkItem w : delivery) {
            Signals s = signals.get(w.getId());
            if (s == null || !seen.add(w.getId())) {
                continue;
            }
            if (s.blocked()) {
                blocked.add(summary(charts, w));
            }
            if (s.stale()) {
                stale.add(summary(charts, w));
            }
            // The item's own question, so a PRD does not repeat what its task is already asking.
            WorkItemAgentSession own = s.agentActivity();
            if (own != null && WorkItemAgentSession.ACTIVITY_NEEDS_INPUT.equals(own.getActivity())) {
                needsInput.add(summary(charts, w));
            }
        }
        return new Overview(activeItems, milestoneService.list(projectId),
                new Attention(blocked, stale, needsInput));
    }

    private static ItemSummary summary(StatechartCache charts, WorkItem item) {
        String label = charts.statusOf(item).map(s -> s.displayLabel()).orElse(item.getCurrentStatus());
        return new ItemSummary(item, label);
    }
}
