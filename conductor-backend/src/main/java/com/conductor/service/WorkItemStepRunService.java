package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemStepRun;
import com.conductor.entity.User;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemStepRunRepository;
import com.conductor.service.view.StepRunInput;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stores and serves the agent-run step-run records the P0-6 Review gate renders (COND-18 E3). The nested
 * produced[]/flags[]/beforeAfter arrive and are persisted as JSONB (the controller owns the typed↔JSON
 * translation); everything else is a scalar column.
 */
@Service
public class WorkItemStepRunService {

    private final WorkItemStepRunRepository workItemStepRunRepository;
    private final WorkItemRepository workItemRepository;
    private final ProjectSecurityService projectSecurityService;

    public WorkItemStepRunService(WorkItemStepRunRepository workItemStepRunRepository,
                          WorkItemRepository workItemRepository,
                          ProjectSecurityService projectSecurityService) {
        this.workItemStepRunRepository = workItemStepRunRepository;
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
    }

    @Transactional(readOnly = true)
    public List<WorkItemStepRun> listStepRuns(String projectId, String workItemId, User caller) {
        verifyMembership(projectId, caller.getId());
        findWorkItemInProject(projectId, workItemId);
        return workItemStepRunRepository.findAllByWorkItemIdOrderByCreatedAtDesc(workItemId);
    }

    @Transactional
    public WorkItemStepRun createStepRun(String projectId, String workItemId, StepRunInput input, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);

        WorkItemStepRun stepRun = new WorkItemStepRun();
        stepRun.setWorkItem(workItem);
        stepRun.setWorkflow(input.workflow());
        stepRun.setFromStatus(input.fromStatus());
        stepRun.setToStatus(input.toStatus());
        stepRun.setStepKind(input.stepKind());
        stepRun.setSkill(input.skill());
        stepRun.setStatus(input.status());
        stepRun.setInputBrief(input.inputBrief());
        stepRun.setReportedBy(input.reportedBy());
        stepRun.setStartedAt(input.startedAt());
        stepRun.setFinishedAt(input.finishedAt());
        stepRun.setProduced(input.produced());
        stepRun.setBeforeAfter(input.beforeAfter());
        stepRun.setFlags(input.flags());
        workItemStepRunRepository.save(stepRun);
        return stepRun;
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
