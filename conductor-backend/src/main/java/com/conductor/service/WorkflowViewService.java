package com.conductor.service;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.generated.model.WorkflowMetricView;
import com.conductor.generated.model.WorkflowStatusView;
import com.conductor.generated.model.WorkflowTransitionView;
import com.conductor.generated.model.WorkflowVersionChangeSummary;
import com.conductor.generated.model.WorkflowVersionSummary;
import com.conductor.generated.model.WorkflowVersionsResponse;
import com.conductor.generated.model.WorkflowView;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartMetric;
import com.conductor.workflow.lifecycle.StatechartStatus;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-model service (CQRS query side) that projects a resolved {@link Statechart} into the lean
 * {@code WorkflowView} the UI consumes, and lists a Workflow's published version history. Resolves by slug so
 * built-in workflows (which have no DB row) are covered alongside project-authored ones.
 */
@Service
public class WorkflowViewService {

    private final WorkflowDefinitionResolver resolver;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowDefinitionVersionRepository versionRepository;
    private final WorkItemRepository workItemRepository;

    public WorkflowViewService(WorkflowDefinitionResolver resolver,
                               ProjectSecurityService projectSecurityService,
                               WorkflowDefinitionRepository definitionRepository,
                               WorkflowDefinitionVersionRepository versionRepository,
                               WorkItemRepository workItemRepository) {
        this.resolver = resolver;
        this.projectSecurityService = projectSecurityService;
        this.definitionRepository = definitionRepository;
        this.versionRepository = versionRepository;
        this.workItemRepository = workItemRepository;
    }

    @Transactional(readOnly = true)
    public WorkflowView getView(String projectId, String slug, Integer version, String callerId) {
        if (!projectSecurityService.isProjectMember(projectId, callerId)) {
            throw new EntityNotFoundException("Workflow not found");
        }
        Statechart statechart = resolver.resolveRequired(projectId, slug, version);
        return toView(statechart);
    }

    /**
     * Bulk-count Work Items bound to each lifecycle Workflow in {@code defs}, keyed by statechart slug. Used to
     * enrich the workflow list response without an N+1 count-per-workflow. Automations and slugless rows are
     * skipped; absent slugs simply have no entry (caller defaults to 0).
     */
    @Transactional(readOnly = true)
    public Map<String, Long> workItemCountsBySlug(List<WorkflowDefinition> defs) {
        Set<String> slugs = defs.stream()
                .filter(WorkflowDefinition::isLifecycle)
                .map(this::extractSlug)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (slugs.isEmpty()) return Map.of();
        return workItemRepository.countGroupedByWorkflowSlug(slugs)
                .stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));
    }

    @Transactional(readOnly = true)
    public WorkflowVersionsResponse listVersions(String projectId, String workflowId, String callerId) {
        if (!projectSecurityService.isProjectMember(projectId, callerId)) {
            throw new EntityNotFoundException("Workflow not found");
        }
        WorkflowDefinition def = definitionRepository.findById(workflowId)
                .filter(d -> d.getProject() != null && projectId.equals(d.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Workflow not found"));

        List<WorkflowDefinitionVersion> rawVersions =
                versionRepository.findByWorkflowDefinitionIdOrderByVersionDesc(def.getId());

        String slug = def.isLifecycle() ? extractSlug(def) : null;
        long totalWorkItems = slug != null ? workItemRepository.countByWorkflowSlug(slug) : 0;
        Integer activeVersion = def.getVersion();

        List<WorkflowVersionSummary> summaries = new ArrayList<>();
        for (int i = 0; i < rawVersions.size(); i++) {
            WorkflowDefinitionVersion current = rawVersions.get(i);
            // Versions are newest-first, so the previous (older) version is the next element.
            WorkflowDefinitionVersion older = (i + 1 < rawVersions.size()) ? rawVersions.get(i + 1) : null;

            Set<String> currentIds = extractStatusIds(current.getDefinition());
            Set<String> olderIds = older != null ? extractStatusIds(older.getDefinition()) : Set.of();

            List<String> added = currentIds.stream().filter(id -> !olderIds.contains(id)).toList();
            List<String> removed = olderIds.stream().filter(id -> !currentIds.contains(id)).toList();

            WorkflowVersionChangeSummary changeSummary = new WorkflowVersionChangeSummary()
                    .statusesAdded(added)
                    .statusesRemoved(removed);

            long wiCount = slug != null
                    ? workItemRepository.countByWorkflowSlugAndVersion(slug, current.getVersion())
                    : 0;

            summaries.add(new WorkflowVersionSummary()
                    .version(current.getVersion())
                    .schemaVersion(current.getSchemaVersion())
                    .publishedAt(current.getPublishedAt())
                    .publishedBy(current.getPublishedBy())
                    .active(current.getVersion().equals(activeVersion))
                    .workItemCount((int) wiCount)
                    .changeSummary(changeSummary));
        }

        return new WorkflowVersionsResponse()
                .activeVersion(activeVersion)
                .totalWorkItems((int) totalWorkItems)
                .versions(summaries);
    }

    /** The statechart slug ({@code definition.id}) from a persisted definition, or null if absent. */
    private String extractSlug(WorkflowDefinition def) {
        JsonNode definition = def.getDefinition();
        if (definition == null) return null;
        JsonNode id = definition.get("id");
        return id != null && id.isTextual() ? id.asText() : null;
    }

    private Set<String> extractStatusIds(JsonNode definition) {
        Set<String> ids = new LinkedHashSet<>();
        if (definition == null) return ids;
        JsonNode statusesNode = definition.get("statuses");
        if (statusesNode != null && statusesNode.isArray()) {
            statusesNode.forEach(n -> {
                JsonNode idNode = n.get("id");
                if (idNode != null && idNode.isTextual()) ids.add(idNode.asText());
            });
        }
        return ids;
    }

    private WorkflowView toView(Statechart statechart) {
        WorkflowView view = new WorkflowView();
        view.setSlug(statechart.slug());
        view.setNoun(statechart.noun());
        view.setArea(statechart.area());
        view.setDefaultView(statechart.defaultView());
        view.setVersion(statechart.version());
        view.setTypes(statechart.types());
        view.setAssetTypes(statechart.assetTypes());
        view.setStatuses(statechart.statuses().stream().map(this::toStatusView).toList());
        view.setTransitions(statechart.transitions().stream().map(this::toTransitionView).toList());
        StatechartMetric metric = statechart.metric();
        if (metric != null) {
            WorkflowMetricView metricView = new WorkflowMetricView();
            metricView.setName(metric.name());
            metricView.setUnit(metric.unit());
            metricView.setDirection(metric.direction());
            view.setMetric(metricView);
        }
        return view;
    }

    private WorkflowStatusView toStatusView(StatechartStatus s) {
        WorkflowStatusView view = new WorkflowStatusView();
        view.setId(s.id());
        view.setLabel(s.displayLabel());
        view.setCategory(s.category());
        view.setInitial(s.initial());
        view.setTerminal(s.terminal());
        return view;
    }

    private WorkflowTransitionView toTransitionView(StatechartTransition t) {
        WorkflowTransitionView view = new WorkflowTransitionView();
        view.setFrom(t.from());
        view.setTo(t.to());
        view.setLabel(t.label());
        view.setRequiresReview(t.requiresReview());
        view.setReviewerRole(t.reviewerRole());
        view.setTrigger(t.trigger());
        return view;
    }
}
