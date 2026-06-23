package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.entity.WorkflowRun;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.WorkflowRunLogBroker;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Public SSE endpoint for streaming live workflow-run logs to the frontend. External API: app-JWT
 * authenticated, project-membership gated, served under {@code /api/v1} (the prefix is supplied by
 * {@link com.conductor.config.ApiPathConfig}; the mapping here is bare). Emitter/log mechanics live in
 * the shared {@link WorkflowRunLogBroker}; the internal worker callbacks that feed it are a separate
 * controller in {@code com.conductor.internal}.
 */
@RestController
public class WorkflowLogStreamController {

    private final WorkflowRunRepository runRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowRunLogBroker broker;

    public WorkflowLogStreamController(WorkflowRunRepository runRepository,
                                       ProjectSecurityService projectSecurityService,
                                       WorkflowRunLogBroker broker) {
        this.runRepository = runRepository;
        this.projectSecurityService = projectSecurityService;
        this.broker = broker;
    }

    @GetMapping(value = "/workflow-runs/{runId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable String runId) {
        String userId = currentUserId();

        WorkflowRun run = runRepository.findByIdWithWorkflow(runId)
                .orElseThrow(() -> new EntityNotFoundException("Run not found: " + runId));

        String projectId = run.getWorkflow().getProject().getId();
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new ForbiddenException("Not a project member");
        }

        return broker.register(run);
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User user)) {
            throw new ClassCastException("Expected User principal but got: " +
                    (principal == null ? "null" : principal.getClass().getName()));
        }
        return user.getId();
    }
}
