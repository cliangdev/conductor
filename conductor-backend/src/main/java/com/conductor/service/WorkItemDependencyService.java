package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemDependency;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.repository.WorkItemDependencyRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.workflow.lifecycle.Statechart;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Links between Work Items: {@code A BLOCKS B} and {@code C DISCOVERED_FROM D}. A row reads "{@code item}
 * {@code kind} {@code related}"; the inverse ({@code blockedBy}, {@code discovered}) is only a read
 * projection.
 *
 * <p>Rules on write: both ends in the same project, never an item and itself, {@code BLOCKS} acyclic
 * (checked transitively, 409), and at most {@value #MAX_BLOCKS_PER_ITEM} outgoing and
 * {@value #MAX_BLOCKS_PER_ITEM} incoming {@code BLOCKS} links per item (409) -- the point is a readable ready
 * queue, not a modelling language. Adding a link that already exists returns it, so a skill can re-assert.
 * Access control is the caller's job (the controller checks the project role).
 */
@Service
public class WorkItemDependencyService {

    /** The most {@code BLOCKS} links an item may have going out, and the most coming in. */
    public static final int MAX_BLOCKS_PER_ITEM = 25;

    private final WorkItemRepository workItemRepository;
    private final WorkItemDependencyRepository dependencyRepository;
    private final WorkItemWorkflowService workItemWorkflowService;

    public WorkItemDependencyService(WorkItemRepository workItemRepository,
                                     WorkItemDependencyRepository dependencyRepository,
                                     WorkItemWorkflowService workItemWorkflowService) {
        this.workItemRepository = workItemRepository;
        this.dependencyRepository = dependencyRepository;
        this.workItemWorkflowService = workItemWorkflowService;
    }

    /** One link seen from an item: its id and the Work Item on the other end. */
    public record Entry(String dependencyId, WorkItem item) {
    }

    /** The links around one item, grouped by what they mean to it. */
    public record Dependencies(List<Entry> blocks, List<Entry> blockedBy, List<Entry> discoveredFrom,
                               List<Entry> discovered) {
    }

    /** A node of the dependency graph; {@code external} marks a blocker that sits outside the subtree. */
    public record GraphNode(WorkItem item, String statusCategory, boolean external) {
    }

    /** A {@code BLOCKS} edge: {@code from} blocks {@code to}. */
    public record GraphEdge(String id, String from, String to, String kind) {
    }

    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) {
    }

    /**
     * Checks that every id names a Work Item of this project, for blockers named on a create: run before the
     * item exists so a bad id is a 400 naming it, not a 404 for the item being created.
     *
     * @throws BusinessException 400 for the first blank, unknown or foreign id
     */
    @Transactional(readOnly = true)
    public void requireBlockersInProject(String projectId, java.util.Collection<String> blockerIds) {
        if (blockerIds == null) {
            return;
        }
        for (String id : blockerIds) {
            if (id == null || id.isBlank() || inProject(projectId, id).isEmpty()) {
                throw new BusinessException("blockedBy Work Item " + id + " was not found in this project");
            }
        }
    }

    /**
     * Records "{@code itemId} {@code kind} {@code relatedId}" and returns the stored link, or the existing
     * one when the same link is already there.
     *
     * @throws BusinessException 400: unknown kind, the item itself, or an item that is not in this project
     * @throws ConflictException 409: a {@code BLOCKS} cycle, or too many {@code BLOCKS} links on either end
     */
    @Transactional
    public WorkItemDependency add(String projectId, String itemId, String kind, String relatedId,
                                  ProjectActor actor) {
        if (!WorkItemDependency.KIND_BLOCKS.equals(kind) && !WorkItemDependency.KIND_DISCOVERED_FROM.equals(kind)) {
            throw new BusinessException("kind must be BLOCKS or DISCOVERED_FROM, got '" + kind + "'");
        }
        if (relatedId == null || relatedId.isBlank()) {
            throw new BusinessException("relatedWorkItemId is required");
        }
        if (itemId.equals(relatedId)) {
            throw new BusinessException("A Work Item cannot depend on itself");
        }
        WorkItem item = inProject(projectId, itemId)
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        WorkItem related = inProject(projectId, relatedId)
                .orElseThrow(() -> new BusinessException(
                        "Work Item " + relatedId + " was not found in this project"));

        Optional<WorkItemDependency> existing =
                dependencyRepository.findByWorkItemIdAndRelatedWorkItemIdAndKind(itemId, relatedId, kind);
        if (existing.isPresent()) {
            return existing.get();
        }

        if (WorkItemDependency.KIND_BLOCKS.equals(kind)) {
            if (dependencyRepository.countByWorkItemIdAndKind(itemId, kind) >= MAX_BLOCKS_PER_ITEM) {
                throw new ConflictException(displayId(item) + " already blocks " + MAX_BLOCKS_PER_ITEM
                        + " Work Items, the most one item may block");
            }
            if (dependencyRepository.countByRelatedWorkItemIdAndKind(relatedId, kind) >= MAX_BLOCKS_PER_ITEM) {
                throw new ConflictException(displayId(related) + " is already blocked by " + MAX_BLOCKS_PER_ITEM
                        + " Work Items, the most one item may be blocked by");
            }
            // item BLOCKS related closes a loop exactly when related can already reach item.
            if (dependencyRepository.isReachableViaBlocks(relatedId, itemId)) {
                throw new ConflictException(displayId(item) + " cannot block " + displayId(related)
                        + ": " + displayId(related) + " already blocks " + displayId(item)
                        + " (directly or through other items), which would create a cycle");
            }
        }

        WorkItemDependency link = new WorkItemDependency();
        link.setProjectId(projectId);
        link.setWorkItemId(itemId);
        link.setRelatedWorkItemId(relatedId);
        link.setKind(kind);
        link.setCreatedBy(actor == null ? null : actor.userId());
        link.setCreatedByLabel(actor == null ? null : actor.label());
        return dependencyRepository.save(link);
    }

    /** Removes a link on this item (either end counts); 404 when it is not one of this item's. */
    @Transactional
    public void remove(String projectId, String itemId, String dependencyId) {
        inProject(projectId, itemId).orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        WorkItemDependency link = dependencyRepository.findById(dependencyId)
                .filter(d -> projectId.equals(d.getProjectId())
                        && (itemId.equals(d.getWorkItemId()) || itemId.equals(d.getRelatedWorkItemId())))
                .orElseThrow(() -> new EntityNotFoundException("Dependency not found"));
        dependencyRepository.delete(link);
    }

    /** Every link around the item, grouped {@code blocks / blockedBy / discoveredFrom / discovered}. */
    @Transactional(readOnly = true)
    public Dependencies listFor(String projectId, String itemId) {
        inProject(projectId, itemId).orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        List<WorkItemDependency> outgoing = dependencyRepository.findByWorkItemIdOrderByCreatedAtAsc(itemId);
        List<WorkItemDependency> incoming = dependencyRepository.findByRelatedWorkItemIdOrderByCreatedAtAsc(itemId);

        Set<String> otherIds = new LinkedHashSet<>();
        outgoing.forEach(d -> otherIds.add(d.getRelatedWorkItemId()));
        incoming.forEach(d -> otherIds.add(d.getWorkItemId()));
        Map<String, WorkItem> others = new HashMap<>();
        if (!otherIds.isEmpty()) {
            workItemRepository.findAllWithAssociationsByIdIn(otherIds).forEach(w -> others.put(w.getId(), w));
        }

        List<Entry> blocks = new ArrayList<>();
        List<Entry> blockedBy = new ArrayList<>();
        List<Entry> discoveredFrom = new ArrayList<>();
        List<Entry> discovered = new ArrayList<>();
        for (WorkItemDependency d : outgoing) {
            WorkItem other = others.get(d.getRelatedWorkItemId());
            if (other == null) {
                continue;
            }
            (WorkItemDependency.KIND_BLOCKS.equals(d.getKind()) ? blocks : discoveredFrom)
                    .add(new Entry(d.getId(), other));
        }
        for (WorkItemDependency d : incoming) {
            WorkItem other = others.get(d.getWorkItemId());
            if (other == null) {
                continue;
            }
            (WorkItemDependency.KIND_BLOCKS.equals(d.getKind()) ? blockedBy : discovered)
                    .add(new Entry(d.getId(), other));
        }
        return new Dependencies(blocks, blockedBy, discoveredFrom, discovered);
    }

    /**
     * The {@code BLOCKS} graph of the branch under {@code rootId} (the root included): the subtree's items as
     * nodes, the {@code BLOCKS} edges whose blocked end is in the subtree, and a ghost node
     * ({@code external}) for each blocker that sits outside it, so a task waiting on another PRD's work still
     * shows what it waits on.
     */
    @Transactional(readOnly = true)
    public Graph graph(String projectId, String rootId) {
        WorkItem root = inProject(projectId, rootId)
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        List<String> subtree = workItemRepository.findSubtreeIds(root.getId());
        Set<String> inSubtree = new LinkedHashSet<>(subtree);
        List<WorkItemDependency> links = dependencyRepository.findBlocksInto(subtree);

        Set<String> ghostIds = new LinkedHashSet<>();
        for (WorkItemDependency d : links) {
            if (!inSubtree.contains(d.getWorkItemId())) {
                ghostIds.add(d.getWorkItemId());
            }
        }
        List<String> wanted = new ArrayList<>(subtree);
        wanted.addAll(ghostIds);
        Map<String, WorkItem> byId = new HashMap<>();
        workItemRepository.findAllWithAssociationsByIdIn(wanted).forEach(w -> byId.put(w.getId(), w));

        Map<String, Optional<Statechart>> charts = new HashMap<>();
        List<GraphNode> nodes = new ArrayList<>();
        for (String id : wanted) {
            WorkItem w = byId.get(id);
            if (w == null) {
                continue;
            }
            nodes.add(new GraphNode(w, categoryOf(projectId, w, charts), ghostIds.contains(id)));
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (WorkItemDependency d : links) {
            if (byId.containsKey(d.getWorkItemId()) && byId.containsKey(d.getRelatedWorkItemId())) {
                edges.add(new GraphEdge(d.getId(), d.getWorkItemId(), d.getRelatedWorkItemId(), d.getKind()));
            }
        }
        return new Graph(nodes, edges);
    }

    /** The status category of an item in its own Workflow, or null when the Workflow cannot be resolved. */
    private String categoryOf(String projectId, WorkItem item, Map<String, Optional<Statechart>> charts) {
        Optional<Statechart> chart = charts.computeIfAbsent(item.getWorkflow() + "@" + item.getWorkflowVersion(),
                key -> {
                    try {
                        return Optional.of(workItemWorkflowService.resolveFor(projectId, item));
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                });
        return chart.flatMap(c -> c.status(item.getCurrentStatus())).map(s -> s.category()).orElse(null);
    }

    private Optional<WorkItem> inProject(String projectId, String id) {
        return workItemRepository.findById(id)
                .filter(w -> w.getProject() != null && projectId.equals(w.getProject().getId()));
    }

    private static String displayId(WorkItem item) {
        return item.getProject().getKey() + "-" + item.getSequenceNumber();
    }
}
