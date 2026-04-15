package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.SettingsApi;
import com.conductor.generated.model.DiscordTestResponse;
import com.conductor.generated.model.ProjectSettingsResponse;
import com.conductor.generated.model.UpdateProjectSettingsRequest;
import com.conductor.service.ProjectSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ProjectSettingsController implements SettingsApi {

    private final ProjectSettingsService projectSettingsService;

    public ProjectSettingsController(ProjectSettingsService projectSettingsService) {
        this.projectSettingsService = projectSettingsService;
    }

    @Override
    public ResponseEntity<ProjectSettingsResponse> updateProjectSettings(
            String projectId, UpdateProjectSettingsRequest request) {
        User caller = currentUser();
        ProjectSettingsResponse response = projectSettingsService.updateSettings(
                projectId, request.getDiscordWebhookUrl(), request.getRunTokenTtlHours(),
                request.getGithubWebhookSecret(), request.getGithubRepoUrl(), caller);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ProjectSettingsResponse> getProjectSettings(String projectId) {
        User caller = currentUser();
        ProjectSettingsResponse response = projectSettingsService.getSettings(projectId, caller);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<DiscordTestResponse> testDiscordWebhook(String projectId) {
        User caller = currentUser();
        DiscordTestResponse response = projectSettingsService.testDiscordWebhook(projectId, caller);
        return ResponseEntity.ok(response);
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
