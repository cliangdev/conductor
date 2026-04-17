package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.OrgMember;
import com.conductor.entity.Organization;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.ProjectVisibility;
import com.conductor.entity.Team;
import com.conductor.entity.TeamMember;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.ProjectDetail;
import com.conductor.generated.model.ProjectResponse;
import com.conductor.generated.model.UpdateProjectRequest;
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.TeamMemberRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectVisibilityTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @Mock
    private OrgMemberRepository orgMemberRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @InjectMocks
    private ProjectService projectService;

    private User orgMemberUser;
    private User nonMemberUser;
    private User projectAdminUser;
    private Organization org;
    private Project orgVisibleProject;
    private Project privateProject;
    private Project legacyProject;
    private Project teamVisibleProject;
    private Team team;
    private ProjectMember adminMembership;

    @BeforeEach
    void setUp() {
        orgMemberUser = new User();
        orgMemberUser.setId("org-member-id");
        orgMemberUser.setEmail("orgmember@example.com");
        orgMemberUser.setName("Org Member");

        nonMemberUser = new User();
        nonMemberUser.setId("non-member-id");
        nonMemberUser.setEmail("nonmember@example.com");
        nonMemberUser.setName("Non Member");

        projectAdminUser = new User();
        projectAdminUser.setId("admin-id");
        projectAdminUser.setEmail("admin@example.com");
        projectAdminUser.setName("Admin");

        org = new Organization();
        org.setId("org-1");
        org.setName("Test Org");
        org.setSlug("test-org");

        team = new Team();
        team.setId("team-1");
        team.setName("Test Team");
        team.setOrg(org);

        orgVisibleProject = new Project();
        orgVisibleProject.setId("proj-org");
        orgVisibleProject.setName("Org Project");
        orgVisibleProject.setKey("ORG");
        orgVisibleProject.setOrgId("org-1");
        orgVisibleProject.setVisibility(ProjectVisibility.ORG);
        orgVisibleProject.setCreatedBy(projectAdminUser);
        orgVisibleProject.setCreatedAt(OffsetDateTime.now());
        orgVisibleProject.setUpdatedAt(OffsetDateTime.now());

        privateProject = new Project();
        privateProject.setId("proj-private");
        privateProject.setName("Private Project");
        privateProject.setKey("PRIV");
        privateProject.setOrgId("org-1");
        privateProject.setVisibility(ProjectVisibility.PRIVATE);
        privateProject.setCreatedBy(projectAdminUser);
        privateProject.setCreatedAt(OffsetDateTime.now());
        privateProject.setUpdatedAt(OffsetDateTime.now());

        legacyProject = new Project();
        legacyProject.setId("proj-legacy");
        legacyProject.setName("Legacy Project");
        legacyProject.setKey("LEG");
        legacyProject.setOrgId(null);
        legacyProject.setVisibility(ProjectVisibility.PRIVATE);
        legacyProject.setCreatedBy(projectAdminUser);
        legacyProject.setCreatedAt(OffsetDateTime.now());
        legacyProject.setUpdatedAt(OffsetDateTime.now());

        teamVisibleProject = new Project();
        teamVisibleProject.setId("proj-team");
        teamVisibleProject.setName("Team Project");
        teamVisibleProject.setKey("TEAM");
        teamVisibleProject.setOrgId("org-1");
        teamVisibleProject.setTeamId("team-1");
        teamVisibleProject.setVisibility(ProjectVisibility.TEAM);
        teamVisibleProject.setCreatedBy(projectAdminUser);
        teamVisibleProject.setCreatedAt(OffsetDateTime.now());
        teamVisibleProject.setUpdatedAt(OffsetDateTime.now());

        adminMembership = new ProjectMember();
        adminMembership.setId("pm-admin");
        adminMembership.setProject(orgVisibleProject);
        adminMembership.setUser(projectAdminUser);
        adminMembership.setRole(MemberRole.ADMIN);
        adminMembership.setJoinedAt(OffsetDateTime.now());
    }

    // --- canUserAccessProject unit tests ---

    @Test
    void orgVisibleProject_accessibleToOrgMemberNotExplicitProjectMember() {
        OrgMember membership = new OrgMember();
        membership.setOrg(org);
        membership.setRole(OrgMember.OrgRole.MEMBER);

        when(projectMemberRepository.existsByProjectIdAndUserId("proj-org", "org-member-id")).thenReturn(false);
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "org-member-id")).thenReturn(Optional.of(membership));

        boolean result = projectService.canUserAccessProject("org-member-id", orgVisibleProject);

        assertThat(result).isTrue();
    }

    @Test
    void privateProject_notAccessibleToOrgMemberWhoIsNotExplicitProjectMember() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-private", "org-member-id")).thenReturn(false);

        boolean result = projectService.canUserAccessProject("org-member-id", privateProject);

        assertThat(result).isFalse();
    }

    @Test
    void legacyProject_notAccessibleToNonProjectMember() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-legacy", "non-member-id")).thenReturn(false);

        boolean result = projectService.canUserAccessProject("non-member-id", legacyProject);

        assertThat(result).isFalse();
    }

    @Test
    void legacyProject_accessibleToExplicitProjectMember() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-legacy", "admin-id")).thenReturn(true);

        boolean result = projectService.canUserAccessProject("admin-id", legacyProject);

        assertThat(result).isTrue();
    }

    @Test
    void teamVisibleProject_accessibleToTeamMemberNotExplicitProjectMember() {
        TeamMember teamMembership = new TeamMember();
        teamMembership.setTeam(team);
        teamMembership.setRole(TeamMember.TeamRole.MEMBER);

        when(projectMemberRepository.existsByProjectIdAndUserId("proj-team", "org-member-id")).thenReturn(false);
        when(teamMemberRepository.findByTeamIdAndUserId("team-1", "org-member-id")).thenReturn(Optional.of(teamMembership));

        boolean result = projectService.canUserAccessProject("org-member-id", teamVisibleProject);

        assertThat(result).isTrue();
    }

    @Test
    void teamVisibleProject_notAccessibleToNonTeamMember() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-team", "non-member-id")).thenReturn(false);
        when(teamMemberRepository.findByTeamIdAndUserId("team-1", "non-member-id")).thenReturn(Optional.empty());

        boolean result = projectService.canUserAccessProject("non-member-id", teamVisibleProject);

        assertThat(result).isFalse();
    }

    // --- getProject integration: throws ForbiddenException for inaccessible project ---

    @Test
    void getProject_throwsForbiddenForPrivateProjectNonMember() {
        when(projectRepository.findById("proj-private")).thenReturn(Optional.of(privateProject));
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-private", "org-member-id")).thenReturn(false);

        assertThatThrownBy(() -> projectService.getProject("proj-private", orgMemberUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getProject_allowsOrgMemberToAccessOrgVisibleProject() {
        OrgMember membership = new OrgMember();
        membership.setOrg(org);
        membership.setRole(OrgMember.OrgRole.MEMBER);

        when(projectRepository.findById("proj-org")).thenReturn(Optional.of(orgVisibleProject));
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-org", "org-member-id")).thenReturn(false);
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "org-member-id")).thenReturn(Optional.of(membership));
        when(projectMemberRepository.findByProjectId("proj-org")).thenReturn(List.of(adminMembership));

        ProjectDetail detail = projectService.getProject("proj-org", orgMemberUser);

        assertThat(detail).isNotNull();
        assertThat(detail.getId()).isEqualTo("proj-org");
    }

    @Test
    void getProject_throwsForbiddenForLegacyProjectNonMember() {
        when(projectRepository.findById("proj-legacy")).thenReturn(Optional.of(legacyProject));
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-legacy", "non-member-id")).thenReturn(false);

        assertThatThrownBy(() -> projectService.getProject("proj-legacy", nonMemberUser))
                .isInstanceOf(ForbiddenException.class);
    }

    // --- PATCH /projects/{projectId}: updateProject ---

    @Test
    void updateProject_adminCanChangeVisibility() {
        when(projectSecurityService.isProjectAdmin("proj-org", "admin-id")).thenReturn(true);
        when(projectRepository.findById("proj-org")).thenReturn(Optional.of(orgVisibleProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProjectRequest request = new UpdateProjectRequest()
                .visibility(UpdateProjectRequest.VisibilityEnum.PRIVATE);

        ProjectResponse response = projectService.updateProject("proj-org", request, projectAdminUser);

        assertThat(response).isNotNull();
        assertThat(orgVisibleProject.getVisibility()).isEqualTo(ProjectVisibility.PRIVATE);
    }

    @Test
    void updateProject_nonAdminThrowsAccessDenied() {
        when(projectSecurityService.isProjectAdmin("proj-org", "org-member-id")).thenReturn(false);

        UpdateProjectRequest request = new UpdateProjectRequest()
                .visibility(UpdateProjectRequest.VisibilityEnum.PRIVATE);

        assertThatThrownBy(() -> projectService.updateProject("proj-org", request, orgMemberUser))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateProject_teamVisibilityWithoutTeamIdThrowsBusinessException() {
        when(projectSecurityService.isProjectAdmin("proj-org", "admin-id")).thenReturn(true);
        when(projectRepository.findById("proj-org")).thenReturn(Optional.of(orgVisibleProject));

        UpdateProjectRequest request = new UpdateProjectRequest()
                .visibility(UpdateProjectRequest.VisibilityEnum.TEAM);

        assertThatThrownBy(() -> projectService.updateProject("proj-org", request, projectAdminUser))
                .isInstanceOf(com.conductor.exception.BusinessException.class)
                .hasMessageContaining("teamId is required");
    }

    @Test
    void updateProject_teamVisibilityWithTeamIdSucceeds() {
        when(projectSecurityService.isProjectAdmin("proj-org", "admin-id")).thenReturn(true);
        when(projectRepository.findById("proj-org")).thenReturn(Optional.of(orgVisibleProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProjectRequest request = new UpdateProjectRequest()
                .visibility(UpdateProjectRequest.VisibilityEnum.TEAM)
                .teamId("team-1");

        ProjectResponse response = projectService.updateProject("proj-org", request, projectAdminUser);

        assertThat(response).isNotNull();
        assertThat(orgVisibleProject.getVisibility()).isEqualTo(ProjectVisibility.TEAM);
        assertThat(orgVisibleProject.getTeamId()).isEqualTo("team-1");
    }
}
