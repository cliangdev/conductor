package com.conductor.v2.controller;

import com.conductor.entity.WorkItem;
import com.conductor.generated.v2.api.DeliveryApi;
import com.conductor.generated.v2.model.DeliveryActiveItem;
import com.conductor.generated.v2.model.DeliveryAttention;
import com.conductor.generated.v2.model.DeliveryItemSummary;
import com.conductor.generated.v2.model.DeliveryOverviewResponse;
import com.conductor.generated.v2.model.WorkItemPriority;
import com.conductor.generated.v2.model.WorkItemProgress;
import com.conductor.service.DeliveryOverviewService;
import com.conductor.service.DeliveryOverviewService.ItemSummary;
import com.conductor.service.PriorityScale;
import com.conductor.service.ProgressRollup.Progress;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** {@code GET /api/v2/projects/{projectId}/delivery-overview}: the Overview page in one call. */
@RestController
public class DeliveryOverviewController implements DeliveryApi {

    private final DeliveryOverviewService overviewService;
    private final ProjectSecurityService projectSecurityService;

    public DeliveryOverviewController(DeliveryOverviewService overviewService,
                                      ProjectSecurityService projectSecurityService) {
        this.overviewService = overviewService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<DeliveryOverviewResponse> getDeliveryOverview(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
        DeliveryOverviewService.Overview overview = overviewService.overview(projectId);
        List<DeliveryActiveItem> active = overview.activeItems().stream()
                .map(a -> new DeliveryActiveItem(summary(a.item()), progress(a.progress()),
                        DeliveryMappers.forecast(a.forecast()), DeliveryMappers.signals(a.signals()))
                        .health(DeliveryMappers.health(a.health())))
                .toList();
        DeliveryAttention attention = new DeliveryAttention(
                overview.attention().blocked().stream().map(DeliveryOverviewController::summary).toList(),
                overview.attention().stale().stream().map(DeliveryOverviewController::summary).toList(),
                overview.attention().needsInput().stream().map(DeliveryOverviewController::summary).toList());
        return ResponseEntity.ok(new DeliveryOverviewResponse(active,
                overview.milestones().stream().map(MilestoneController::toResponse).toList(), attention));
    }

    private static WorkItemProgress progress(Progress p) {
        return new WorkItemProgress(p.total(), p.done(), p.percent(), p.criteriaTotal(), p.criteriaMet());
    }

    private static DeliveryItemSummary summary(ItemSummary s) {
        WorkItem item = s.item();
        return new DeliveryItemSummary(item.getId(), DeliveryMappers.displayId(item), item.getTitle(),
                item.getType(), item.getCurrentStatus())
                .statusLabel(s.statusLabel())
                .workflow(item.getWorkflow())
                .priority(item.getPriority() == null ? null
                        : WorkItemPriority.fromValue(PriorityScale.label(item.getPriority())))
                .parentWorkItemId(item.getParentWorkItemId())
                .milestoneId(item.getMilestone() == null ? null : item.getMilestone().getId())
                .milestoneName(item.getMilestone() == null ? null : item.getMilestone().getName())
                .appetite(item.getAppetite())
                .appetiteDays(item.getAppetiteDays())
                .createdAt(item.getCreatedAt())
                .startedAt(item.getStartedAt())
                .blockedByCount(s.blockedByCount());
    }
}
