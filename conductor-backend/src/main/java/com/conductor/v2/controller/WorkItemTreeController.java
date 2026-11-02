package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.generated.v2.api.WorkItemTreeApi;
import com.conductor.generated.v2.model.WorkItemAssignee;
import com.conductor.generated.v2.model.WorkItemPriority;
import com.conductor.generated.v2.model.WorkItemTreeNode;
import com.conductor.service.PriorityScale;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.WorkItemHierarchyService;
import com.conductor.service.WorkItemHierarchyService.TreeNode;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

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

    public WorkItemTreeController(WorkItemHierarchyService hierarchyService,
                                  ProjectSecurityService projectSecurityService) {
        this.hierarchyService = hierarchyService;
        this.projectSecurityService = projectSecurityService;
    }

    // Open-in-view is disabled, so the mapping (which reads lazy associations) runs inside the transaction.
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<WorkItemTreeNode> getWorkItemTree(String projectId, String workItemId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(toNode(projectId, hierarchyService.tree(projectId, workItemId)));
    }

    private static WorkItemTreeNode toNode(String projectId, TreeNode node) {
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
                node.children().stream().map(child -> toNode(projectId, child)).toList())
                .workflow(item.getWorkflow())
                .statusLabel(node.statusLabel())
                .priority(item.getPriority() == null ? null
                        : WorkItemPriority.fromValue(PriorityScale.label(item.getPriority())))
                .parentWorkItemId(item.getParentWorkItemId())
                .milestoneId(item.getMilestone() == null ? null : item.getMilestone().getId())
                .assignee(assignee);
    }
}
