package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.StepRun;
import com.conductor.entity.User;
import com.conductor.generated.model.CreateStepRunRequest;
import com.conductor.generated.model.StepRunBeforeAfter;
import com.conductor.generated.model.StepRunProduced;
import com.conductor.generated.model.StepRunResponse;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.StepRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stores and serves the agent-run step-run records the P0-6 Review gate renders (COND-18 E3). The nested
 * produced[]/flags[]/beforeAfter are persisted as JSONB; everything else is a scalar column.
 */
@Service
public class StepRunService {

    private final StepRunRepository stepRunRepository;
    private final WorkItemRepository workItemRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ObjectMapper objectMapper;

    public StepRunService(StepRunRepository stepRunRepository,
                          WorkItemRepository workItemRepository,
                          ProjectSecurityService projectSecurityService,
                          ObjectMapper objectMapper) {
        this.stepRunRepository = stepRunRepository;
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<StepRunResponse> listStepRuns(String projectId, String issueId, User caller) {
        verifyMembership(projectId, caller.getId());
        findIssueInProject(projectId, issueId);
        return stepRunRepository.findAllByWorkItemIdOrderByCreatedAtDesc(issueId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional
    public StepRunResponse createStepRun(String projectId, String issueId, CreateStepRunRequest request, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem issue = findIssueInProject(projectId, issueId);

        StepRun stepRun = new StepRun();
        stepRun.setWorkItem(issue);
        stepRun.setWorkflow(request.getWorkflow());
        stepRun.setFromStatus(request.getFromStatus());
        stepRun.setToStatus(request.getToStatus());
        stepRun.setStepKind(request.getStepKind().getValue());
        stepRun.setSkill(request.getSkill());
        stepRun.setStatus(request.getStatus().getValue());
        stepRun.setInputBrief(request.getInputBrief());
        stepRun.setReportedBy(request.getReportedBy());
        stepRun.setStartedAt(request.getStartedAt());
        stepRun.setFinishedAt(request.getFinishedAt());
        stepRun.setProduced(toJson(request.getProduced()));
        stepRun.setBeforeAfter(toJson(request.getBeforeAfter()));
        stepRun.setFlags(toJson(request.getFlags()));
        stepRunRepository.save(stepRun);
        return toResponse(stepRun);
    }

    private JsonNode toJson(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    private StepRunResponse toResponse(StepRun stepRun) {
        StepRunResponse response = new StepRunResponse(
                stepRun.getId(), stepRun.getWorkItem().getId(), stepRun.getStatus(),
                stepRun.getInputBrief(), stepRun.getReportedBy(), stepRun.getCreatedAt());
        response.setWorkflow(stepRun.getWorkflow());
        response.setFromStatus(stepRun.getFromStatus());
        response.setToStatus(stepRun.getToStatus());
        response.setStepKind(stepRun.getStepKind());
        response.setSkill(stepRun.getSkill());
        response.setStartedAt(stepRun.getStartedAt());
        response.setFinishedAt(stepRun.getFinishedAt());
        if (stepRun.getProduced() != null) {
            response.setProduced(objectMapper.convertValue(stepRun.getProduced(),
                    new TypeReference<List<StepRunProduced>>() {}));
        }
        if (stepRun.getBeforeAfter() != null) {
            response.setBeforeAfter(objectMapper.convertValue(stepRun.getBeforeAfter(), StepRunBeforeAfter.class));
        }
        if (stepRun.getFlags() != null) {
            response.setFlags(objectMapper.convertValue(stepRun.getFlags(),
                    new TypeReference<List<com.conductor.generated.model.StepRunFlag>>() {}));
        }
        return response;
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
