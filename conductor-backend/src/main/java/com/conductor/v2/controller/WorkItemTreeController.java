package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.generated.v2.api.WorkItemTreeApi;
import com.conductor.generated.v2.model.WorkItemAssignee;
import com.conductor.generated.v2.model.WorkItemPriority;
import com.conductor.generated.v2.model.WorkItemTreeNode;
import com.conductor.service.PriorityScale;
import com.conductor.service.ProgressRollup.CriteriaCount;
import com.conductor.service.WorkItemSignalService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.WorkItemHierarchyService;
import com.conductor.service.WorkItemHierarchyService.TreeNode;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Work Item and everything beneath it ({@code GET /api/v2/projects/{projectId}/work-items/{id}/tree}).
 * The walk itself lives in {@link WorkItemHierarchyService}; this controller checks access and maps the
 * result to {@link WorkItemTreeNode}.
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig}, so this class maps at bare
 * paths via the generated interface.
 */
@RestController
public class WorkItemTreeController implements WorkItemTreeApi {

    private final WorkItemHierarchyService hierarchyService;
    private final ProjectSecurityService projectSecurityService;
    private final WorkItemSignalService signalService;

    public WorkItemTreeController(WorkItemHierarchyService hierarchyService,
                                  ProjectSecurityService projectSecurityService,
                                  WorkItemSignalService signalService) {
        this.hierarchyService = hierarchyService;
        this.projectSecurityService = projectSecurityService;
        this.signalService = signalService;
    }

    // Open-in-view is disabled, so the mapping (which reads lazy associations) runs inside the transaction.
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<WorkItemTreeNode> getWorkItemTree(String projectId, String workItemId) {
        projectSecurityService.requireProjectAccess(projectId);
        TreeNode root = hierarchyService.tree(projectId, workItemId);
        // Signals and criteria for every node in a few batched reads rather than one set per node.
        List<WorkItem> all = new ArrayList<>();
        collect(root, all);
        Map<String, WorkItemSignalService.Signals> signals = signalService.signalsFor(projectId, all);
        Map<String, CriteriaCount> criteria =
                hierarchyService.criteriaCounts(all.stream().map(WorkItem::getId).toList());
        return ResponseEntity.ok(toNode(projectId, root, signals, criteria));
    }

    private static void collect(TreeNode node, List<WorkItem> out) {
        out.add(node.item());
        node.children().forEach(child -> collect(child, out));
    }

    private static WorkItemTreeNode toNode(String projectId, TreeNode node,
                                           Map<String, WorkItemSignalService.Signals> signals,
                                           Map<String, CriteriaCount> criteria) {
        WorkItem item = node.item();
        WorkItemAssignee assignee = null;
        if (item.getAssignee() != null) {
            User a = item.getAssignee();
            assignee = new WorkItemAssignee(a.getId(), a.getName()).avatarUrl(a.getAvatarUrl());
        }
        return new WorkItemTreeNode(
                item.getId(),
                item.getProject().getKey() + "-" + item.getSequenceNumber(),
                item.getTitle(),
                item.getType(),
                item.getCurrentStatus(),
                node.terminal(),
                node.children().size(),
                node.children().stream().map(child -> toNode(projectId, child, signals, criteria)).toList())
                .workflow(item.getWorkflow())
                .statusLabel(node.statusLabel())
                .priority(item.getPriority() == null ? null
                        : WorkItemPriority.fromValue(PriorityScale.label(item.getPriority())))
                .parentWorkItemId(item.getParentWorkItemId())
                .milestoneId(item.getMilestone() == null ? null : item.getMilestone().getId())
                .assignee(assignee)
                .criteriaTotal(criteria.getOrDefault(item.getId(), new CriteriaCount(0, 0)).total())
                .criteriaMet(criteria.getOrDefault(item.getId(), new CriteriaCount(0, 0)).met())
                .signals(signals.containsKey(item.getId())
                        ? DeliveryMappers.signals(signals.get(item.getId())) : null)
                .agentActivity(signals.containsKey(item.getId())
                        ? DeliveryMappers.agentActivity(signals.get(item.getId()).agentActivity()) : null);
    }
}
