package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.api.DaemonApi;
import com.conductor.generated.model.AckDaemonEventsRequest;
import com.conductor.generated.model.CompleteWorkflowJobRequest;
import com.conductor.generated.model.DaemonEventDto;
import com.conductor.generated.model.DaemonEventsResponse;
import com.conductor.generated.model.JobDispatchPayloadDto;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.security.ProjectScopedPrincipal;
import com.conductor.security.WorkflowRunAuthenticationToken;
import com.conductor.service.DaemonEventService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.JobDispatchPayloadService;
import com.conductor.workflow.WorkflowJobOrchestrator;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class DaemonEventsController implements DaemonApi {

    private final DaemonEventService daemonEventService;
    private final JobDispatchPayloadService jobDispatchPayloadService;
    private final WorkflowJobOrchestrator workflowJobOrchestrator;
    private final WorkflowRunRepository runRepository;
    private final ProjectSecurityService projectSecurityService;

    public DaemonEventsController(DaemonEventService daemonEventService,
                                   JobDispatchPayloadService jobDispatchPayloadService,
                                   WorkflowJobOrchestrator workflowJobOrchestrator,
                                   WorkflowRunRepository runRepository,
                                   ProjectSecurityService projectSecurityService) {
        this.daemonEventService = daemonEventService;
        this.jobDispatchPayloadService = jobDispatchPayloadService;
        this.workflowJobOrchestrator = workflowJobOrchestrator;
        this.runRepository = runRepository;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<DaemonEventsResponse> getDaemonEvents(String projectId) {
        List<DaemonEventDto> events = daemonEventService.getDaemonEvents(projectId);
        DaemonEventsResponse response = new DaemonEventsResponse();
        response.setEvents(events);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> ackDaemonEvents(String projectId, AckDaemonEventsRequest ackDaemonEventsRequest) {
        daemonEventService.acknowledgeEvents(projectId, ackDaemonEventsRequest.getEventIds());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<JobDispatchPayloadDto> getJobDispatchPayload(String runId, String jobId) {
        requireProjectApiKeyScopeForRun(runId);
        return ResponseEntity.ok(jobDispatchPayloadService.buildPayload(runId, jobId));
    }

    @Override
    public ResponseEntity<Void> completeWorkflowJob(String runId, String jobId, CompleteWorkflowJobRequest request) {
        requireProjectApiKeyScopeForRun(runId);
        WorkflowJobStatus terminalStatus = WorkflowJobStatus.valueOf(request.getStatus());
        workflowJobOrchestrator.completeRemoteJob(runId, jobId, terminalStatus, request.getErrorReason());
        return ResponseEntity.ok().build();
    }

    /**
     * Daemon endpoints are authenticated by a project-scoped principal (a project API key or a
     * run-scoped MCP token) or by a user (user API key / JWT) who edits the run's project, but runId/jobId don't carry the project in the path — unlike
     * getDaemonEvents/ackDaemonEvents which trust a caller-supplied projectId path segment, these load
     * the run and verify the authenticated principal's project actually matches it. A run-scoped
     * token is additionally pinned to its own run: unlike a project API key, it was minted for one
     * runId and must not act on siblings.
     */
    private void requireProjectApiKeyScopeForRun(String runId) {
        WorkflowRun run = runRepository.findByIdWithWorkflow(runId)
                .orElseThrow(() -> new EntityNotFoundException("Run not found: " + runId));
        String projectId = run.getWorkflow().getProject().getId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // A daemon logged in with `conductor login` authenticates with the user's API key, not a project
        // key: accept an ADMIN/CREATOR member of the run's project (a non-member or REVIEWER gets a 403).
        if (auth != null && auth.getPrincipal() instanceof User) {
            projectSecurityService.requireProjectEditor(projectId);
            return;
        }
        if (!(auth instanceof ProjectScopedPrincipal scoped) || !projectId.equals(scoped.getProjectId())) {
            throw new ForbiddenException("API key does not belong to project " + projectId);
        }
        if (auth instanceof WorkflowRunAuthenticationToken runToken && !runId.equals(runToken.getRunId())) {
            throw new ForbiddenException("Run token does not belong to run " + runId);
        }
    }
}
