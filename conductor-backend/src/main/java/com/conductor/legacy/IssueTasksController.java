package com.conductor.legacy;

import com.conductor.entity.User;
import com.conductor.generated.api.TasksApi;
import com.conductor.generated.model.SaveTasks200Response;
import com.conductor.service.WorkItemService;
import com.conductor.service.ProjectSecurityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class IssueTasksController implements TasksApi {

    private final WorkItemService workItemService;
    private final ProjectSecurityService projectSecurityService;
    private final ObjectMapper objectMapper;

    public IssueTasksController(WorkItemService workItemService,
                                ProjectSecurityService projectSecurityService,
                                ObjectMapper objectMapper) {
        this.workItemService = workItemService;
        this.projectSecurityService = projectSecurityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<SaveTasks200Response> saveTasks(String projectId, String issueId, Map<String, Object> requestBody) {
        User caller = currentUser();
        verifyMembership(projectId, caller.getId());
        JsonNode tasksNode = objectMapper.valueToTree(requestBody);
        workItemService.saveIssueTasks(issueId, tasksNode);
        SaveTasks200Response response = new SaveTasks200Response().message("saved");
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Map<String, Object>> getTasks(String projectId, String issueId) {
        User caller = currentUser();
        verifyMembership(projectId, caller.getId());
        JsonNode tasksNode = workItemService.getIssueTasks(issueId);
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
