package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.TasksApi;
import com.conductor.generated.model.SaveTasks200Response;
import com.conductor.service.IssueService;
import com.conductor.service.ProjectSecurityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class IssueTasksController implements TasksApi {

    private final IssueService issueService;
    private final ProjectSecurityService projectSecurityService;
    private final ObjectMapper objectMapper;

    public IssueTasksController(IssueService issueService,
                                ProjectSecurityService projectSecurityService,
                                ObjectMapper objectMapper) {
        this.issueService = issueService;
        this.projectSecurityService = projectSecurityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<SaveTasks200Response> saveTasks(String projectId, String issueId, Map<String, Object> requestBody) {
        User caller = currentUser();
        verifyMembership(projectId, caller.getId());
        JsonNode tasksNode = objectMapper.valueToTree(requestBody);
        issueService.saveIssueTasks(issueId, tasksNode);
        SaveTasks200Response response = new SaveTasks200Response().message("saved");
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Map<String, Object>> getTasks(String projectId, String issueId) {
        User caller = currentUser();
        verifyMembership(projectId, caller.getId());
        JsonNode tasksNode = issueService.getIssueTasks(issueId);
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
