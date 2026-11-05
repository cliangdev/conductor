package com.conductor.v2.controller;

import com.conductor.generated.v2.api.WorkItemAgentSessionsApi;
import com.conductor.generated.v2.model.UpsertAgentSessionRequest;
import com.conductor.generated.v2.model.WorkItemAgentSessionResponse;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.WorkItemAgentSessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent sessions ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/agent-sessions}). Logic lives
 * in {@link WorkItemAgentSessionService}; reads need project access, the upsert an editor (machine
 * credentials pass both, so a skill running under a project key or run token can report).
 */
@RestController
public class WorkItemAgentSessionsController implements WorkItemAgentSessionsApi {

    private final WorkItemAgentSessionService sessionService;
    private final ProjectSecurityService projectSecurityService;

    public WorkItemAgentSessionsController(WorkItemAgentSessionService sessionService,
                                           ProjectSecurityService projectSecurityService) {
        this.sessionService = sessionService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<List<WorkItemAgentSessionResponse>> listWorkItemAgentSessions(String projectId,
                                                                                        String workItemId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(sessionService.list(projectId, workItemId).stream()
                .map(DeliveryMappers::session).toList());
    }

    @Override
    public ResponseEntity<WorkItemAgentSessionResponse> upsertWorkItemAgentSession(
            String projectId, String workItemId, String sessionKey, UpsertAgentSessionRequest request) {
        projectSecurityService.requireProjectEditor(projectId);
        return ResponseEntity.ok(DeliveryMappers.session(sessionService.upsert(projectId, workItemId, sessionKey,
                request.getAgentLabel(), request.getMode() == null ? null : request.getMode().getValue(),
                request.getActivity().getValue(), request.getNote(), request.getStepRunId())));
    }
}
