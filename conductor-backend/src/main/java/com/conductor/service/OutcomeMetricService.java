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
 * issue (the issue_tasks precedent); the metric's name/unit/direction come from the bound Workflow definition.
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
    public OutcomeMetricView getMetric(String projectId, String issueId, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem issue = findIssueInProject(projectId, issueId);
        return buildView(projectId, issue);
    }

    @Transactional
    public OutcomeMetricView record(String projectId, String issueId,
                                    Double value, OffsetDateTime observedAt, String note, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem issue = findIssueInProject(projectId, issueId);

        List<Observation> observations = readObservations(issue);
        observations.add(new Observation(value, observedAt != null ? observedAt : OffsetDateTime.now(), note));

        issue.setOutcomeMetric(objectMapper.valueToTree(observations));
        workItemRepository.save(issue);
        return buildView(projectId, issue);
    }

    private List<Observation> readObservations(WorkItem issue) {
        JsonNode stored = issue.getOutcomeMetric();
        if (stored == null || stored.isNull()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(objectMapper.convertValue(stored, new TypeReference<List<Observation>>() {}));
    }

    private OutcomeMetricView buildView(String projectId, WorkItem issue) {
        String slug = issue.getWorkflow() != null ? issue.getWorkflow() : WorkItemWorkflowService.DEFAULT_WORKFLOW;
        Statechart statechart = resolver.resolveRequired(projectId, slug, issue.getWorkflowVersion());
        StatechartMetric metric = statechart.metric();
        return new OutcomeMetricView(
                readObservations(issue),
                metric != null ? metric.name() : null,
                metric != null ? metric.unit() : null,
                metric != null ? metric.direction() : null);
    }

    private void verifyMembership(String projectId, String userId) {
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Issue not found");
        }
    }

    private WorkItem findIssueInProject(String projectId, String issueId) {
        return workItemRepository.findById(issueId)
                .filter(i -> i.getProject() != null && projectId.equals(i.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));
    }
}
