package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.WorkItemCriterionRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProgressRollup.CriteriaCount;
import com.conductor.service.ProgressRollup.Node;
import com.conductor.service.ProgressRollup.Progress;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The Work Item tree: who is whose parent, how far a branch has got, and rolling completion up.
 *
 * <p>Parentage is the plain {@code parent_work_item_id} column; the tree is read with a recursive CTE
 * ({@link WorkItemRepository#findSubtreeIds}) rather than lazy walks. An item at the top of the tree is at
 * depth 1, and a branch may be at most {@value #MAX_DEPTH} levels deep.
 *
 * <p>Kept out of {@link WorkItemService} on purpose, and therefore knows nothing of notifications: the
 * status moves it makes are returned ({@link ParentTransition}) for the caller to publish.
 */
@Service
public class WorkItemHierarchyService {

    private static final Logger log = LoggerFactory.getLogger(WorkItemHierarchyService.class);

    /** The deepest a branch may go, counting the top of the tree as level 1. */
    public static final int MAX_DEPTH = 5;

    /**
     * Terminal statuses that mean "abandoned", not "delivered", matched on status id: the cancel-style ids the
     * built-in Workflows use ({@code CANCELLED} on TASK, {@code CLOSED} on ENGINEERING). The statechart cannot
     * say so itself -- {@code terminal} covers both -- so this is a naming convention; a custom Workflow that
     * wants the same treatment names its cancel status one of these.
     */
    static final java.util.Set<String> CANCEL_STYLE_STATUSES = java.util.Set.of("CANCELLED", "CLOSED");

    /** The Workflow a child lands on when its creator names none. */
    public static final String TASK_WORKFLOW = "TASK";
    public static final String TASK_TYPE = "TASK";

    private final WorkItemRepository workItemRepository;
    private final WorkItemWorkflowService workItemWorkflowService;
    private final WorkItemCriterionRepository criterionRepository;

    public WorkItemHierarchyService(WorkItemRepository workItemRepository,
                                    WorkItemWorkflowService workItemWorkflowService,
                                    WorkItemCriterionRepository criterionRepository) {
        this.workItemRepository = workItemRepository;
        this.workItemWorkflowService = workItemWorkflowService;
        this.criterionRepository = criterionRepository;
    }

    /** A parent moved forward because its last child finished. */
    public record ParentTransition(WorkItem parent, String fromStatus, String toStatus) {
    }

    /** A Work Item in a tree read, with the two facts about its status a client cannot derive itself. */
    public record TreeNode(WorkItem item, String statusLabel, boolean terminal, List<TreeNode> children) {
    }

    // --- parentage ---

    /**
     * Points {@code item} at a new parent, or detaches it when {@code parentWorkItemId} is blank. Refuses a
     * parent from another project, the item itself, a parent that sits beneath the item (a cycle), and a
     * result deeper than {@value #MAX_DEPTH} levels once the item's own subtree is counted.
     */
    public void setParent(String projectId, WorkItem item, String parentWorkItemId) {
        if (parentWorkItemId == null || parentWorkItemId.isBlank()) {
            item.setParentWorkItemId(null);
            return;
        }
        WorkItem parent = validateParent(projectId, item, parentWorkItemId);
        item.setParentWorkItemId(parent.getId());
    }

    /**
     * Validates {@code parentWorkItemId} as a parent for {@code item} (null for an item not yet created) and
     * returns it. Throws 400 for a missing or foreign parent, the item itself, or a branch too deep; 409 for
     * a cycle.
     */
    public WorkItem validateParent(String projectId, WorkItem item, String parentWorkItemId) {
        String itemId = item == null ? null : item.getId();
        if (parentWorkItemId.equals(itemId)) {
            throw new BusinessException("A Work Item cannot be its own parent");
        }
        WorkItem parent = workItemRepository.findById(parentWorkItemId)
                .filter(p -> p.getProject() != null && projectId.equals(p.getProject().getId()))
                .orElseThrow(() -> new BusinessException(
                        "Parent Work Item " + parentWorkItemId + " was not found in this project"));

        // Walk up from the parent. Meeting the item on the way means the new parent is its own descendant.
        int parentDepth = 1;
        WorkItem cursor = parent;
        while (cursor.getParentWorkItemId() != null) {
            if (cursor.getParentWorkItemId().equals(itemId)) {
                throw new ConflictException("Cannot make that item the parent: it sits beneath this one, "
                        + "which would create a cycle");
            }
            if (++parentDepth > MAX_DEPTH) {
                break;
            }
            cursor = workItemRepository.findById(cursor.getParentWorkItemId()).orElse(null);
            if (cursor == null) {
                break;
            }
        }
        int below = itemId == null ? 0 : heightBelow(itemId);
        if (parentDepth + 1 + below > MAX_DEPTH) {
            throw new BusinessException("Work Items can be nested at most " + MAX_DEPTH + " levels deep");
        }
        return parent;
    }

    /** Levels beneath {@code rootId}: 0 for a leaf, 1 when it only has direct children, and so on. */
    private int heightBelow(String rootId) {
        List<String> ids = workItemRepository.findSubtreeIds(rootId);
        if (ids.size() <= 1) {
            return 0;
        }
        Map<String, Integer> depth = new HashMap<>();
        depth.put(rootId, 0);
        Map<String, String> parentOf = new HashMap<>();
        for (WorkItem w : workItemRepository.findAllById(ids)) {
            parentOf.put(w.getId(), w.getParentWorkItemId());
        }
        int max = 0;
        // The CTE yields parents before children, so one pass resolves every depth.
        for (String id : ids) {
            String parentId = parentOf.get(id);
            if (parentId != null && depth.containsKey(parentId)) {
                int d = depth.get(parentId) + 1;
                depth.put(id, d);
                max = Math.max(max, d);
            }
        }
        return max;
    }

    // --- counts, progress, tree ---

    /** Direct-child counts for these items; an item with no children is absent from the map. */
    @Transactional(readOnly = true)
    public Map<String, Integer> childCounts(Collection<String> ids) {
        Map<String, Integer> counts = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return counts;
        }
        for (Object[] row : workItemRepository.countChildrenGrouped(ids)) {
            counts.put((String) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * How far the branch under {@code rootId} has got: its leaf items done over total, across the whole
     * subtree. An item with no children yields {@link Progress#EMPTY}.
     */
    @Transactional(readOnly = true)
    public Progress progress(String projectId, String rootId) {
        return progressFor(projectId, List.of(rootId)).get(rootId);
    }

    /** Met and total acceptance criteria for these items in one query; items with none are absent. */
    @Transactional(readOnly = true)
    public Map<String, CriteriaCount> criteriaCounts(Collection<String> ids) {
        Map<String, CriteriaCount> counts = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return counts;
        }
        for (Object[] row : criterionRepository.countsByWorkItem(ids)) {
            counts.put((String) row[0], new CriteriaCount(((Number) row[2]).intValue(), ((Number) row[1]).intValue()));
        }
        return counts;
    }

    /**
     * {@link #progress} for each of these roots, keyed by id, in a fixed number of reads however many roots
     * there are: one subtree walk, one load of the items, one criteria count. A root with no children (or no
     * such item) yields {@link Progress#EMPTY}.
     */
    @Transactional(readOnly = true)
    public Map<String, Progress> progressFor(String projectId, Collection<String> rootIds) {
        Map<String, Progress> byRoot = new LinkedHashMap<>();
        if (rootIds == null || rootIds.isEmpty()) {
            return byRoot;
        }
        Map<String, List<String>> subtrees = new HashMap<>();
        for (Object[] row : workItemRepository.findSubtreeIdsFor(rootIds)) {
            subtrees.computeIfAbsent((String) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }
        Set<String> allIds = new HashSet<>();
        subtrees.values().stream().filter(ids -> ids.size() > 1).forEach(allIds::addAll);

        Map<String, WorkItem> items = new HashMap<>();
        Map<String, CriteriaCount> criteria = Map.of();
        if (!allIds.isEmpty()) {
            workItemRepository.findAllById(allIds).forEach(w -> items.put(w.getId(), w));
            criteria = criteriaCounts(allIds);
        }
        StatechartCache charts = new StatechartCache(workItemWorkflowService, projectId);
        for (String rootId : rootIds) {
            List<String> ids = subtrees.getOrDefault(rootId, List.of());
            if (ids.size() <= 1) {
                byRoot.put(rootId, Progress.EMPTY);
                continue;
            }
            List<Node> nodes = new ArrayList<>();
            for (String id : ids) {
                WorkItem w = items.get(id);
                if (w != null) {
                    nodes.add(new Node(id, id.equals(rootId) ? null : w.getParentWorkItemId(),
                            charts.isTerminal(w)));
                }
            }
            byRoot.put(rootId, ProgressRollup.of(rootId, nodes, criteria));
        }
        return byRoot;
    }

    /** The ids among these items whose status is terminal in their own Workflow. */
    @Transactional(readOnly = true)
    public java.util.Set<String> terminalIds(String projectId, Collection<WorkItem> items) {
        StatechartCache charts = new StatechartCache(workItemWorkflowService, projectId);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (WorkItem item : items) {
            if (charts.isTerminal(item)) {
                ids.add(item.getId());
            }
        }
        return ids;
    }

    /** The branch under {@code rootId} (the root included) as a nested structure, siblings in creation order. */
    @Transactional(readOnly = true)
    public TreeNode tree(String projectId, String rootId) {
        WorkItem root = workItemRepository.findById(rootId)
                .filter(w -> w.getProject() != null && projectId.equals(w.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        List<String> ids = workItemRepository.findSubtreeIds(root.getId());
        Map<String, List<WorkItem>> childrenOf = new HashMap<>();
        Map<String, WorkItem> byId = new HashMap<>();
        for (WorkItem w : workItemRepository.findAllWithAssociationsByIdIn(ids)) {
            byId.put(w.getId(), w);
            if (!w.getId().equals(root.getId()) && w.getParentWorkItemId() != null) {
                childrenOf.computeIfAbsent(w.getParentWorkItemId(), k -> new ArrayList<>()).add(w);
            }
        }
        childrenOf.values().forEach(list -> list.sort(Comparator.comparing(WorkItem::getSequenceNumber)));
        return buildNode(byId.getOrDefault(root.getId(), root), childrenOf,
                new StatechartCache(workItemWorkflowService, projectId));
    }

    private TreeNode buildNode(WorkItem item, Map<String, List<WorkItem>> childrenOf, StatechartCache charts) {
        List<TreeNode> children = childrenOf.getOrDefault(item.getId(), List.of()).stream()
                .map(child -> buildNode(child, childrenOf, charts))
                .toList();
        String label = charts.statusOf(item).map(s -> s.displayLabel()).orElse(item.getCurrentStatus());
        return new TreeNode(item, label, charts.isTerminal(item), children);
    }

    // --- completion roll-up ---

    /**
     * Call after a Work Item's status has been saved. If it has a parent on the {@value #TASK_WORKFLOW}
     * Workflow and every one of that parent's direct children is now terminal, the parent takes its
     * {@code children_done} edge (normally to DONE), and the same check repeats on the grandparent, and so
     * on upward. Returns the moves made, in order, for the caller to publish.
     *
     * <p>At least one child must have finished in a status other than the cancel-style ones
     * ({@link #CANCEL_STYLE_STATUSES}): a parent whose children were all cancelled is left as it is, for a
     * person to finish or cancel.
     *
     * <p>Only ever forward and only ever upward: a parent already finished is left alone (the edge does not
     * leave a terminal status), a child reopening never reopens its parent, and nothing here touches a
     * child. A parent on any other Workflow -- a PRD on ENGINEERING completes when its pull request merges --
     * stops the walk. A gate that refuses the move (required fields) leaves the parent where it was.
     */
    public List<ParentTransition> onChildStatusChanged(String projectId, WorkItem child) {
        List<ParentTransition> applied = new ArrayList<>();
        WorkItem current = child;
        StatechartCache charts = new StatechartCache(workItemWorkflowService, projectId);
        for (int level = 0; level < MAX_DEPTH && current.getParentWorkItemId() != null; level++) {
            WorkItem parent = workItemRepository.findById(current.getParentWorkItemId())
                    .filter(p -> p.getProject() != null && projectId.equals(p.getProject().getId()))
                    .orElse(null);
            if (parent == null || !TASK_WORKFLOW.equals(parent.getWorkflow())) {
                break;
            }
            List<WorkItem> siblings = workItemRepository.findChildren(parent.getId());
            if (siblings.isEmpty() || !siblings.stream().allMatch(charts::isTerminal)) {
                break;
            }
            // All children cancelled: nothing was delivered, so whether the parent is done is a person's call.
            if (siblings.stream().allMatch(s -> CANCEL_STYLE_STATUSES.contains(s.getCurrentStatus()))) {
                break;
            }
            String from = parent.getCurrentStatus();
            Optional<StatechartTransition> moved;
            try {
                moved = workItemWorkflowService.applySystemTransition(
                        projectId, parent, WorkItemWorkflowService.TRIGGER_CHILDREN_DONE);
            } catch (UnprocessableEntityException refused) {
                log.warn("Parent {} not completed after its children finished: {}", parent.getId(),
                        refused.getMessage());
                break;
            }
            if (moved.isEmpty()) {
                break;
            }
            workItemRepository.save(parent);
            applied.add(new ParentTransition(parent, from, parent.getCurrentStatus()));
            current = parent;
        }
        return applied;
    }
}
