package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.InvitesApi;
import com.conductor.generated.model.AcceptInviteResponse;
import com.conductor.generated.model.AcceptOrgInviteResponse;
import com.conductor.generated.model.CreateInviteRequest;
import com.conductor.generated.model.InviteResponse;
import com.conductor.service.InviteService;
import com.conductor.service.OrgInviteService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class InviteController implements InvitesApi {

    private final InviteService inviteService;
    private final OrgInviteService orgInviteService;

    public InviteController(InviteService inviteService, OrgInviteService orgInviteService) {
        this.inviteService = inviteService;
        this.orgInviteService = orgInviteService;
    }

    @Override
    public ResponseEntity<InviteResponse> createInvite(String projectId, CreateInviteRequest createInviteRequest) {
        User caller = currentUser();
        InviteResponse response = inviteService.createInvite(
                projectId,
                createInviteRequest.getEmail(),
                createInviteRequest.getRole(),
                caller);
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<List<InviteResponse>> listInvites(String projectId) {
        User caller = currentUser();
        List<InviteResponse> invites = inviteService.listPendingInvites(projectId, caller);
        return ResponseEntity.ok(invites);
    }

    @Override
    public ResponseEntity<Void> cancelInvite(String projectId, String inviteId) {
        User caller = currentUser();
        inviteService.cancelInvite(projectId, inviteId, caller);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AcceptInviteResponse> acceptInvite(String token) {
        User caller = currentUser();
        AcceptInviteResponse response = inviteService.acceptInvite(token, caller);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<AcceptOrgInviteResponse> acceptOrgInvite(String token) {
        User caller = currentUser();
        AcceptOrgInviteResponse response = orgInviteService.acceptInvite(token, caller);
        return ResponseEntity.ok(response);
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
