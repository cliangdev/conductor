package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.User;
import com.conductor.generated.model.MetricObservation;
import com.conductor.generated.model.OutcomeMetricResponse;
import com.conductor.generated.model.RecordMetricObservationRequest;
import com.conductor.repository.IssueRepository;
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

    private final IssueRepository issueRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowDefinitionResolver resolver;
    private final ObjectMapper objectMapper;

    public OutcomeMetricService(IssueRepository issueRepository,
                                ProjectSecurityService projectSecurityService,
                                WorkflowDefinitionResolver resolver,
                                ObjectMapper objectMapper) {
        this.issueRepository = issueRepository;
        this.projectSecurityService = projectSecurityService;
        this.resolver = resolver;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public OutcomeMetricResponse getMetric(String projectId, String issueId, User caller) {
        verifyMembership(projectId, caller.getId());
        Issue issue = findIssueInProject(projectId, issueId);
        return buildResponse(projectId, issue);
    }

    @Transactional
    public OutcomeMetricResponse record(String projectId, String issueId,
                                        RecordMetricObservationRequest request, User caller) {
        verifyMembership(projectId, caller.getId());
        Issue issue = findIssueInProject(projectId, issueId);

        List<MetricObservation> observations = readObservations(issue);
        MetricObservation observation = new MetricObservation(
                request.getValue(),
                request.getObservedAt() != null ? request.getObservedAt() : OffsetDateTime.now());
        observation.setNote(request.getNote());
        observations.add(observation);

        issue.setOutcomeMetric(objectMapper.valueToTree(observations));
        issueRepository.save(issue);
        return buildResponse(projectId, issue);
    }

    private List<MetricObservation> readObservations(Issue issue) {
        JsonNode stored = issue.getOutcomeMetric();
        if (stored == null || stored.isNull()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(objectMapper.convertValue(stored, new TypeReference<List<MetricObservation>>() {}));
    }

    private OutcomeMetricResponse buildResponse(String projectId, Issue issue) {
        OutcomeMetricResponse response = new OutcomeMetricResponse(readObservations(issue));
        String slug = issue.getWorkflow() != null ? issue.getWorkflow() : WorkItemTransitionService.DEFAULT_WORKFLOW;
        Statechart statechart = resolver.resolveRequired(projectId, slug);
        StatechartMetric metric = statechart.metric();
        if (metric != null) {
            response.setName(metric.name());
            response.setUnit(metric.unit());
            response.setDirection(metric.direction());
        }
        return response;
    }

    private void verifyMembership(String projectId, String userId) {
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Issue not found");
        }
    }

    private Issue findIssueInProject(String projectId, String issueId) {
        return issueRepository.findById(issueId)
                .filter(i -> i.getProject() != null && projectId.equals(i.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));
    }
}
