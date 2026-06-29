package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.generated.v2.api.WorkItemTasksApi;
import com.conductor.generated.v2.model.SaveWorkItemTasks200Response;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.WorkItemService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Canonical v2 Work Item tasks sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/tasks}). Successor to the legacy v1
 * {@code issues/{issueId}/tasks} surface; additive and does not change v1 behavior.
 *
 * <p>All business logic lives in the shared {@link WorkItemService}. The save/get methods exchange a
 * {@link JsonNode} (a JSON blob), not entities, so no transaction is needed in this controller.
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 */
@RestController
public class WorkItemTasksController implements WorkItemTasksApi {

    private final WorkItemService workItemService;
    private final ProjectSecurityService projectSecurityService;
    private final ObjectMapper objectMapper;

    public WorkItemTasksController(WorkItemService workItemService,
                                   ProjectSecurityService projectSecurityService,
                                   ObjectMapper objectMapper) {
        this.workItemService = workItemService;
        this.projectSecurityService = projectSecurityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<SaveWorkItemTasks200Response> saveWorkItemTasks(String projectId, String workItemId,
                                                                          Map<String, Object> requestBody) {
        User caller = currentUser();
        verifyMembership(projectId, caller.getId());
        JsonNode tasksNode = objectMapper.valueToTree(requestBody);
        workItemService.saveIssueTasks(workItemId, tasksNode);
        SaveWorkItemTasks200Response response = new SaveWorkItemTasks200Response().message("saved");
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Map<String, Object>> getWorkItemTasks(String projectId, String workItemId) {
        User caller = currentUser();
        verifyMembership(projectId, caller.getId());
        JsonNode tasksNode = workItemService.getIssueTasks(workItemId);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.convertValue(tasksNode, Map.class);
        return ResponseEntity.ok(result);
    }

    private void verifyMembership(String projectId, String userId) {
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
