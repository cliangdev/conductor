package com.conductor.controller;

import com.conductor.entity.RuntimeTarget;
import com.conductor.entity.User;
import com.conductor.generated.api.RuntimeTargetsApi;
import com.conductor.generated.model.CreateRuntimeTargetRequest;
import com.conductor.generated.model.RuntimeTargetResponse;
import com.conductor.generated.model.UpdateRuntimeTargetRequest;
import com.conductor.security.ProjectScopedPrincipal;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.RuntimeTargetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class RuntimeTargetController implements RuntimeTargetsApi {

    private final RuntimeTargetService runtimeTargetService;
    private final ProjectSecurityService projectSecurityService;

    public RuntimeTargetController(RuntimeTargetService runtimeTargetService,
                                   ProjectSecurityService projectSecurityService) {
        this.runtimeTargetService = runtimeTargetService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<List<RuntimeTargetResponse>> listRuntimeTargets(String projectId) {
        requireMember(projectId);
        List<RuntimeTargetResponse> items = runtimeTargetService.list(projectId).stream()
                .map(this::toResponse).toList();
        return ResponseEntity.ok(items);
    }

    @Override
    public ResponseEntity<RuntimeTargetResponse> createRuntimeTarget(
            String projectId, CreateRuntimeTargetRequest request) {
        requireAdminOrCreator(projectId);
        RuntimeTarget target = runtimeTargetService.create(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(target));
    }

    @Override
    public ResponseEntity<RuntimeTargetResponse> getRuntimeTarget(String projectId, String targetId) {
        requireMember(projectId);
        return ResponseEntity.ok(toResponse(runtimeTargetService.get(projectId, targetId)));
    }

    @Override
    public ResponseEntity<RuntimeTargetResponse> updateRuntimeTarget(
            String projectId, String targetId, UpdateRuntimeTargetRequest request) {
        requireAdminOrCreator(projectId);
        RuntimeTarget target = runtimeTargetService.update(projectId, targetId, request);
        return ResponseEntity.ok(toResponse(target));
    }

    @Override
    public ResponseEntity<Void> deleteRuntimeTarget(String projectId, String targetId) {
        requireAdminOrCreator(projectId);
        runtimeTargetService.delete(projectId, targetId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<RuntimeTargetResponse> provisionRuntimeTarget(String projectId, String targetId) {
        requireAdminOrCreator(projectId);
        RuntimeTarget target = runtimeTargetService.provisionById(projectId, targetId);
        return ResponseEntity.ok(toResponse(target));
    }

    // ---- helpers ----

    private RuntimeTargetResponse toResponse(RuntimeTarget target) {
        return runtimeTargetService.toResponse(target);
    }

    /**
     * Member-level gate: accepts either a {@link User} principal or a project-scoped machine
     * principal ({@link ProjectScopedPrincipal} -- a project API key or a run-scoped MCP token)
     * whose {@code projectId} matches the requested project. The rule itself lives in
     * {@link ProjectSecurityService#requireProjectAccess}, shared with every other project-scoped
     * controller.
     */
    private void requireMember(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
    }

    /**
     * Admin/creator-level gate: only a real {@link User} principal can hold a project role, so
     * project-scoped machine principals are rejected with a clean 403 here -- mirroring
     * {@code KnowledgeController#requireProjectAdmin}.
     */
    private void requireAdminOrCreator(String projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User user) || !projectSecurityService.isAdminOrCreator(projectId, user.getId())) {
            throw new AccessDeniedException("Requires ADMIN or CREATOR role");
        }
    }
}
