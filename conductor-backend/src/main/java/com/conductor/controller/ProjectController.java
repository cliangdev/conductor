package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.ProjectsApi;
import com.conductor.generated.model.CreateProjectRequest;
import com.conductor.generated.model.ProjectDetail;
import com.conductor.generated.model.ProjectResponse;
import com.conductor.generated.model.ProjectSummary;
import com.conductor.service.ProjectService;
import org.springframework.http.ResponseEntity;
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

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
