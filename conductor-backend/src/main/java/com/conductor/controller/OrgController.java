package com.conductor.controller;

import com.conductor.entity.OrgMember;
import com.conductor.entity.Organization;
import com.conductor.entity.User;
import com.conductor.generated.api.OrgsApi;
import com.conductor.generated.model.ChangeOrgMemberRoleRequest;
import com.conductor.generated.model.CreateOrgRequest;
import com.conductor.generated.model.InviteOrgMemberRequest;
import com.conductor.generated.model.MessageResponse;
import com.conductor.generated.model.OrgMemberResponse;
import com.conductor.generated.model.OrgResponse;
import com.conductor.service.OrgMemberService;
import com.conductor.service.OrgService;
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
public class OrgController implements OrgsApi {

    private static final Logger log = LoggerFactory.getLogger(OrgController.class);

    private final OrgService orgService;
    private final OrgMemberService orgMemberService;

    public OrgController(OrgService orgService, OrgMemberService orgMemberService) {
        this.orgService = orgService;
        this.orgMemberService = orgMemberService;
    }

    @Override
    public ResponseEntity<OrgResponse> createOrg(CreateOrgRequest createOrgRequest) {
        User caller = currentUser();
        Organization org = orgService.createOrg(caller.getId(), createOrgRequest.getName(), createOrgRequest.getSlug());
        return ResponseEntity.status(201).body(toOrgResponse(org));
    }

    @Override
    public ResponseEntity<OrgResponse> getOrg(String orgId) {
        User caller = currentUser();
        Organization org = orgService.getOrg(orgId, caller.getId());
        return ResponseEntity.ok(toOrgResponse(org));
    }

    @Override
    public ResponseEntity<List<OrgResponse>> listMyOrgs() {
        User caller = currentUser();
        List<OrgResponse> responses = orgService.getOrgsForUser(caller.getId()).stream()
                .map(this::toOrgResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Override
    public ResponseEntity<List<OrgMemberResponse>> listOrgMembers(String orgId) {
        User caller = currentUser();
        List<OrgMemberResponse> members = orgMemberService.getMembers(orgId, caller.getId()).stream()
                .map(this::toMemberResponse)
                .toList();
        return ResponseEntity.ok(members);
    }

    @Override
    public ResponseEntity<MessageResponse> inviteOrgMember(String orgId, InviteOrgMemberRequest request) {
        User caller = currentUser();
        OrgMember.OrgRole role = OrgMember.OrgRole.valueOf(request.getRole().getValue());
        orgMemberService.inviteMember(orgId, caller.getId(), request.getEmail(), role);
        return ResponseEntity.ok(new MessageResponse("Invitation sent to " + request.getEmail()));
    }

    @Override
    public ResponseEntity<OrgMemberResponse> changeOrgMemberRole(String orgId, String userId, ChangeOrgMemberRoleRequest request) {
        User caller = currentUser();
        OrgMember.OrgRole newRole = OrgMember.OrgRole.valueOf(request.getRole().getValue());
        OrgMemberService.OrgMemberDetails updated = orgMemberService.changeMemberRole(orgId, caller.getId(), userId, newRole);
        return ResponseEntity.ok(toMemberResponse(updated));
    }

    @Override
    public ResponseEntity<Void> removeOrgMember(String orgId, String userId) {
        User caller = currentUser();
        orgMemberService.removeMember(orgId, caller.getId(), userId);
        return ResponseEntity.noContent().build();
    }

    private OrgResponse toOrgResponse(Organization org) {
        return new OrgResponse(org.getId(), org.getName(), org.getSlug(), org.getCreatedAt());
    }

    private OrgMemberResponse toMemberResponse(OrgMemberService.OrgMemberDetails details) {
        return new OrgMemberResponse(
                details.userId(),
                details.name(),
                details.email(),
                OrgMemberResponse.RoleEnum.fromValue(details.role().name()),
                details.joinedAt()
        );
    }

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
