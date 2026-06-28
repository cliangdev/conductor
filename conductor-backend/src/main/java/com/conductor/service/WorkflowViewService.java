package com.conductor.service;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.generated.model.WorkflowMetricView;
import com.conductor.generated.model.WorkflowStatusView;
import com.conductor.generated.model.WorkflowTransitionView;
import com.conductor.generated.model.WorkflowVersionSummary;
import com.conductor.generated.model.WorkflowView;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartMetric;
import com.conductor.workflow.lifecycle.StatechartStatus;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    public WorkflowViewService(WorkflowDefinitionResolver resolver,
                               ProjectSecurityService projectSecurityService,
                               WorkflowDefinitionRepository definitionRepository,
                               WorkflowDefinitionVersionRepository versionRepository) {
        this.resolver = resolver;
        this.projectSecurityService = projectSecurityService;
        this.definitionRepository = definitionRepository;
        this.versionRepository = versionRepository;
    }

    @Transactional(readOnly = true)
    public WorkflowView getView(String projectId, String slug, Integer version, String callerId) {
        if (!projectSecurityService.isProjectMember(projectId, callerId)) {
            throw new EntityNotFoundException("Workflow not found");
        }
        Statechart statechart = resolver.resolveRequired(projectId, slug, version);
        return toView(statechart);
    }

    @Transactional(readOnly = true)
    public List<WorkflowVersionSummary> listVersions(String projectId, String workflowId, String callerId) {
        if (!projectSecurityService.isProjectMember(projectId, callerId)) {
            throw new EntityNotFoundException("Workflow not found");
        }
        WorkflowDefinition def = definitionRepository.findById(workflowId)
                .filter(d -> d.getProject() != null && projectId.equals(d.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Workflow not found"));
        return versionRepository.findByWorkflowDefinitionIdOrderByVersionDesc(def.getId())
                .stream()
                .map(v -> {
                    WorkflowVersionSummary summary = new WorkflowVersionSummary();
                    summary.setVersion(v.getVersion());
                    summary.setSchemaVersion(v.getSchemaVersion());
                    summary.setPublishedAt(v.getPublishedAt());
                    summary.setPublishedBy(v.getPublishedBy());
                    return summary;
                })
                .toList();
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
