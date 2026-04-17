package com.conductor.controller;

import com.conductor.entity.ProjectRepository;
import com.conductor.entity.User;
import com.conductor.generated.api.RepositoriesApi;
import com.conductor.generated.model.AddRepositoryRequest;
import com.conductor.generated.model.ProjectRepositoryResponse;
import com.conductor.generated.model.UpdateRepositoryRequest;
import com.conductor.service.ProjectRepositoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Manual Verification:
// POST /projects/{id}/repositories — requires Admin, returns 201 with ProjectRepositoryResponse
// GET /projects/{id}/repositories — any member, returns 200 with list
// DELETE /projects/{id}/repositories/{repoId} — requires Admin, returns 204
// Non-admin callers receive 403 on POST and DELETE
@RestController
@RequestMapping("/api/v1")
public class ProjectRepositoryController implements RepositoriesApi {

    private final ProjectRepositoryService projectRepositoryService;

    public ProjectRepositoryController(ProjectRepositoryService projectRepositoryService) {
        this.projectRepositoryService = projectRepositoryService;
    }

    @Override
    public ResponseEntity<ProjectRepositoryResponse> addRepository(
            String projectId, AddRepositoryRequest request) {
        User caller = currentUser();
        ProjectRepository repo = projectRepositoryService.addRepository(
                projectId,
                request.getLabel(),
                request.getRepoUrl(),
                request.getWebhookSecret(),
                caller.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(repo));
    }

    @Override
    public ResponseEntity<List<ProjectRepositoryResponse>> listRepositories(String projectId) {
        List<ProjectRepository> repos = projectRepositoryService.listRepositories(projectId);
        List<ProjectRepositoryResponse> response = repos.stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ProjectRepositoryResponse> updateRepository(
            String projectId, String repositoryId, UpdateRepositoryRequest request) {
        User caller = currentUser();
        ProjectRepository repo = projectRepositoryService.updateRepository(
                projectId, repositoryId, request.getLabel(), request.getWebhookSecret(), caller.getId());
        return ResponseEntity.ok(toResponse(repo));
    }

    @Override
    public ResponseEntity<Void> deleteRepository(String projectId, String repositoryId) {
        User caller = currentUser();
        projectRepositoryService.deleteRepository(projectId, repositoryId, caller.getId());
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private ProjectRepositoryResponse toResponse(ProjectRepository repo) {
        return new ProjectRepositoryResponse(
                repo.getId(),
                repo.getLabel(),
                repo.getRepoUrl(),
                repo.getRepoFullName(),
                repo.getWebhookSecret() != null && !repo.getWebhookSecret().isBlank(),
                repo.getConnectedAt());
    }
}
