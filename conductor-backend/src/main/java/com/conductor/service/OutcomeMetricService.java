package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.User;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.view.OutcomeMetricView;
import com.conductor.service.view.OutcomeMetricView.Observation;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartMetric;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The append-only Outcome Metric series on a Work Item (COND-18 E6). Observations are stored as JSONB on the
 * work item (the work_item_tasks precedent); the metric's name/unit/direction come from the bound Workflow definition.
 * Manual web entry and programmatic MCP entry both land here.
 */
@Service
public class OutcomeMetricService {

    private final WorkItemRepository workItemRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowDefinitionResolver resolver;
    private final ObjectMapper objectMapper;

    public OutcomeMetricService(WorkItemRepository workItemRepository,
                                ProjectSecurityService projectSecurityService,
                                WorkflowDefinitionResolver resolver,
                                ObjectMapper objectMapper) {
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
        this.resolver = resolver;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public OutcomeMetricView getMetric(String projectId, String workItemId, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        return buildView(projectId, workItem);
    }

    @Transactional
    public OutcomeMetricView record(String projectId, String workItemId,
                                    Double value, OffsetDateTime observedAt, String note, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);

        List<Observation> observations = readObservations(workItem);
        observations.add(new Observation(value, observedAt != null ? observedAt : OffsetDateTime.now(), note));

        workItem.setOutcomeMetric(objectMapper.valueToTree(observations));
        workItemRepository.save(workItem);
        return buildView(projectId, workItem);
    }

    private List<Observation> readObservations(WorkItem workItem) {
        JsonNode stored = workItem.getOutcomeMetric();
        if (stored == null || stored.isNull()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(objectMapper.convertValue(stored, new TypeReference<List<Observation>>() {}));
    }

    private OutcomeMetricView buildView(String projectId, WorkItem workItem) {
        String slug = workItem.getWorkflow() != null ? workItem.getWorkflow() : WorkItemWorkflowService.DEFAULT_WORKFLOW;
        Statechart statechart = resolver.resolveRequired(projectId, slug, workItem.getWorkflowVersion());
        StatechartMetric metric = statechart.metric();
        return new OutcomeMetricView(
                readObservations(workItem),
                metric != null ? metric.name() : null,
                metric != null ? metric.unit() : null,
                metric != null ? metric.direction() : null);
    }

    private void verifyMembership(String projectId, String userId) {
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Work Item not found");
        }
    }

    private WorkItem findWorkItemInProject(String projectId, String workItemId) {
        return workItemRepository.findById(workItemId)
                .filter(i -> i.getProject() != null && projectId.equals(i.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
    }
}
