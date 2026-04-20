package com.conductor.controller;

import com.conductor.entity.OrgMember;
import com.conductor.entity.Organization;
import com.conductor.entity.Team;
import com.conductor.entity.TeamMember;
import com.conductor.entity.User;
import com.conductor.generated.api.OrgsApi;
import com.conductor.generated.model.AddTeamMemberRequest;
import com.conductor.generated.model.ChangeOrgMemberRoleRequest;
import com.conductor.generated.model.CreateOrgRequest;
import com.conductor.generated.model.CreateTeamRequest;
import com.conductor.generated.model.InviteOrgMemberRequest;
import com.conductor.generated.model.OrgInviteResponse;
import com.conductor.generated.model.OrgMemberResponse;
import com.conductor.generated.model.OrgResponse;
import com.conductor.generated.model.ProjectSummary;
import com.conductor.generated.model.TeamMemberResponse;
import com.conductor.generated.model.TeamResponse;
import com.conductor.service.OrgMemberService;
import com.conductor.service.OrgService;
import com.conductor.service.ProjectService;
import com.conductor.service.TeamService;
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
    private final TeamService teamService;
    private final ProjectService projectService;

    public OrgController(OrgService orgService, OrgMemberService orgMemberService, TeamService teamService, ProjectService projectService) {
        this.orgService = orgService;
        this.orgMemberService = orgMemberService;
        this.teamService = teamService;
        this.projectService = projectService;
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
    public ResponseEntity<OrgInviteResponse> inviteOrgMember(String orgId, InviteOrgMemberRequest request) {
        User caller = currentUser();
        OrgMember.OrgRole role = OrgMember.OrgRole.valueOf(request.getRole().getValue());
        OrgInviteResponse response = orgMemberService.inviteMember(orgId, caller.getId(), request.getEmail(), role);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<List<OrgInviteResponse>> listOrgInvites(String orgId) {
        User caller = currentUser();
        List<OrgInviteResponse> invites = orgMemberService.listPendingInvites(orgId, caller.getId());
        return ResponseEntity.ok(invites);
    }

    @Override
    public ResponseEntity<OrgInviteResponse> resendOrgInvite(String orgId, String inviteId) {
        User caller = currentUser();
        OrgInviteResponse response = orgMemberService.resendInvite(orgId, inviteId, caller.getId());
        return ResponseEntity.ok(response);
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

    @Override
    public ResponseEntity<List<ProjectSummary>> listOrgProjects(String orgId) {
        User caller = currentUser();
        List<ProjectSummary> projects = projectService.listOrgProjects(orgId, caller);
        return ResponseEntity.ok(projects);
    }

    @Override
    public ResponseEntity<TeamResponse> createTeam(String orgId, CreateTeamRequest createTeamRequest) {
        User caller = currentUser();
        Team team = teamService.createTeam(orgId, caller.getId(), createTeamRequest.getName());
        return ResponseEntity.status(201).body(toTeamResponse(team));
    }

    @Override
    public ResponseEntity<List<TeamResponse>> listTeams(String orgId) {
        User caller = currentUser();
        List<TeamResponse> teams = teamService.getTeamsForOrg(orgId, caller.getId()).stream()
                .map(this::toTeamResponse)
                .toList();
        return ResponseEntity.ok(teams);
    }

    @Override
    public ResponseEntity<List<TeamMemberResponse>> listTeamMembers(String teamId) {
        User caller = currentUser();
        List<TeamMemberResponse> members = teamService.getTeamMembers(teamId, caller.getId()).stream()
                .map(this::toTeamMemberResponse)
                .toList();
        return ResponseEntity.ok(members);
    }

    @Override
    public ResponseEntity<TeamMemberResponse> addTeamMember(String teamId, AddTeamMemberRequest addTeamMemberRequest) {
        User caller = currentUser();
        TeamMember.TeamRole role = TeamMember.TeamRole.valueOf(addTeamMemberRequest.getRole().getValue());
        TeamMember member = teamService.addTeamMember(teamId, caller.getId(), addTeamMemberRequest.getUserId(), role);
        return ResponseEntity.status(201).body(toTeamMemberResponse(member));
    }

    @Override
    public ResponseEntity<Void> removeTeamMember(String teamId, String userId) {
        User caller = currentUser();
        teamService.removeTeamMember(teamId, caller.getId(), userId);
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

    private TeamResponse toTeamResponse(Team team) {
        return new TeamResponse(team.getId(), team.getOrg().getId(), team.getName(), team.getCreatedAt());
    }

    private TeamMemberResponse toTeamMemberResponse(TeamService.TeamMemberDetails details) {
        return new TeamMemberResponse(
                details.userId(),
                details.name(),
                details.email(),
                TeamMemberResponse.RoleEnum.fromValue(details.role().name()),
                details.joinedAt()
        );
    }

    private TeamMemberResponse toTeamMemberResponse(TeamMember member) {
        return new TeamMemberResponse(
                member.getUser().getId(),
                member.getUser().getName(),
                member.getUser().getEmail(),
                TeamMemberResponse.RoleEnum.fromValue(member.getRole().name()),
                member.getJoinedAt()
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
