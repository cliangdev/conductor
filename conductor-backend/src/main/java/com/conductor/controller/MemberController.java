package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.MembersApi;
import com.conductor.generated.model.MemberResponse;
import com.conductor.generated.model.UpdateMemberRoleRequest;
import com.conductor.service.ProjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class MemberController implements MembersApi {

    private final ProjectService projectService;

    public MemberController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Override
    public ResponseEntity<List<MemberResponse>> listProjectMembers(String projectId) {
        User caller = currentUser();
        List<MemberResponse> members = projectService.listMembers(projectId, caller);
        return ResponseEntity.ok(members);
    }

    @Override
    public ResponseEntity<MemberResponse> updateMemberRole(String projectId, String userId, UpdateMemberRoleRequest updateMemberRoleRequest) {
        User caller = currentUser();
        MemberResponse response = projectService.updateMemberRole(projectId, userId, updateMemberRoleRequest, caller);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> removeMember(String projectId, String userId) {
        User caller = currentUser();
        projectService.removeMember(projectId, userId, caller);
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
