package com.conductor.v2.controller;

import com.conductor.generated.v2.api.WorkItemDependenciesApi;
import com.conductor.generated.v2.model.AddWorkItemDependencyRequest;
import com.conductor.generated.v2.model.WorkItemDependencies;
import com.conductor.generated.v2.model.WorkItemDependencyEntry;
import com.conductor.generated.v2.model.WorkItemDependencyGraph;
import com.conductor.generated.v2.model.WorkItemDependencyGraphEdge;
import com.conductor.generated.v2.model.WorkItemDependencyGraphNode;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.WorkItemDependencyService;
import com.conductor.service.WorkItemDependencyService.Entry;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Work Item dependencies ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/dependencies} and
 * {@code .../dependency-graph}). The rules (same project, no cycle, caps) live in
 * {@link WorkItemDependencyService}; this controller checks the caller's role and maps to DTOs. Reads need
 * project access, writes an editor (machine credentials pass both).
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig}.
 */
@RestController
public class WorkItemDependenciesController implements WorkItemDependenciesApi {

    private final WorkItemDependencyService dependencyService;
    private final ProjectSecurityService projectSecurityService;

    public WorkItemDependenciesController(WorkItemDependencyService dependencyService,
                                          ProjectSecurityService projectSecurityService) {
        this.dependencyService = dependencyService;
        this.projectSecurityService = projectSecurityService;
    }

    // Open-in-view is off, so the mapping (which reads lazy associations) runs inside the transaction.
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<WorkItemDependencies> listWorkItemDependencies(String projectId, String workItemId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(toDto(dependencyService.listFor(projectId, workItemId)));
    }

    @Override
    @Transactional
    public ResponseEntity<WorkItemDependencies> addWorkItemDependency(String projectId, String workItemId,
                                                                      AddWorkItemDependencyRequest request) {
        ProjectActor actor = projectSecurityService.requireProjectEditor(projectId);
        dependencyService.add(projectId, workItemId, request.getKind().getValue(),
                request.getRelatedWorkItemId(), actor);
        return ResponseEntity.ok(toDto(dependencyService.listFor(projectId, workItemId)));
    }

    @Override
    public ResponseEntity<Void> removeWorkItemDependency(String projectId, String workItemId,
                                                         String dependencyId) {
        projectSecurityService.requireProjectEditor(projectId);
        dependencyService.remove(projectId, workItemId, dependencyId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<WorkItemDependencyGraph> getWorkItemDependencyGraph(String projectId,
                                                                              String workItemId) {
        projectSecurityService.requireProjectAccess(projectId);
        WorkItemDependencyService.Graph graph = dependencyService.graph(projectId, workItemId);
        List<WorkItemDependencyGraphNode> nodes = graph.nodes().stream()
                .map(n -> new WorkItemDependencyGraphNode(n.item().getId(), DeliveryMappers.displayId(n.item()),
                        n.item().getTitle(), n.item().getCurrentStatus(), n.external())
                        .statusCategory(n.statusCategory()))
                .toList();
        List<WorkItemDependencyGraphEdge> edges = graph.edges().stream()
                .map(e -> new WorkItemDependencyGraphEdge(e.id(), e.from(), e.to(), e.kind()))
                .toList();
        return ResponseEntity.ok(new WorkItemDependencyGraph(nodes, edges));
    }

    private static WorkItemDependencies toDto(WorkItemDependencyService.Dependencies d) {
        return new WorkItemDependencies(entries(d.blocks()), entries(d.blockedBy()),
                entries(d.discoveredFrom()), entries(d.discovered()));
    }

    private static List<WorkItemDependencyEntry> entries(List<Entry> list) {
        return list.stream()
                .map(e -> new WorkItemDependencyEntry(e.dependencyId(), e.item().getId(),
                        DeliveryMappers.displayId(e.item()), e.item().getTitle(), e.item().getCurrentStatus()))
                .toList();
    }
}
