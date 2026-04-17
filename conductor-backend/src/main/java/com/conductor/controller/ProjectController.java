package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.ProjectsApi;
import com.conductor.generated.model.CreateProjectRequest;
import com.conductor.generated.model.ProjectDetail;
import com.conductor.generated.model.ProjectResponse;
import com.conductor.generated.model.ProjectSummary;
import com.conductor.generated.model.UpdateProjectRequest;
import com.conductor.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ProjectController implements ProjectsApi {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Override
    public ResponseEntity<ProjectResponse> createProject(CreateProjectRequest createProjectRequest) {
        User caller = currentUser();
        ProjectResponse response = projectService.createProject(createProjectRequest, caller);
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<List<ProjectSummary>> listProjects() {
        User caller = currentUser();
        List<ProjectSummary> projects = projectService.listProjects(caller);
        return ResponseEntity.ok(projects);
    }

    @Override
    public ResponseEntity<ProjectDetail> getProject(String projectId) {
        User caller = currentUser();
        ProjectDetail detail = projectService.getProject(projectId, caller);
        return ResponseEntity.ok(detail);
    }

    @Override
    public ResponseEntity<ProjectResponse> updateProject(String projectId, UpdateProjectRequest updateProjectRequest) {
        User caller = currentUser();
        ProjectResponse response = projectService.updateProject(projectId, updateProjectRequest, caller);
        return ResponseEntity.ok(response);
    }

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User)) {
            log.warn("currentUser() expected User principal but got {} (auth type={})",
                    principal == null ? "null" : principal.getClass().getName(),
                    auth == null ? "null" : auth.getClass().getSimpleName());
            throw new ClassCastException("Expected User principal but got: " +
                    (principal == null ? "null" : principal.getClass().getName()));
        }
        return (User) principal;
    }
}
