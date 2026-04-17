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
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.ProjectDetail;
import com.conductor.generated.model.ProjectResponse;
import com.conductor.generated.model.UpdateProjectRequest;
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.TeamMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for TEAM-visibility access control.
 *
 * Manual verification: After deploying, add user A to a team, assign a project to that team with
 * TEAM visibility. Verify user A can access it. Remove user A from the team. Verify user A can no
 * longer access (gets 403).
 */
@ExtendWith(MockitoExtension.class)
class TeamVisibilityTest {

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

    private User adminUser;
    private User teamMemberUser;
    private User orgOnlyMemberUser;
    private Organization org;
    private Team team;
    private Project teamProject;
    private Project projectWithNoTeam;
    private ProjectMember adminMembership;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId("user-admin");
        adminUser.setEmail("admin@example.com");
        adminUser.setName("Admin");

        teamMemberUser = new User();
        teamMemberUser.setId("user-team-member");
        teamMemberUser.setEmail("teammember@example.com");
        teamMemberUser.setName("Team Member");

        orgOnlyMemberUser = new User();
        orgOnlyMemberUser.setId("user-org-only");
        orgOnlyMemberUser.setEmail("orgonly@example.com");
        orgOnlyMemberUser.setName("Org Only Member");

        org = new Organization();
        org.setId("org-1");
        org.setName("Test Org");
        org.setSlug("test-org");

        team = new Team();
        team.setId("team-1");
        team.setName("Engineering");
        team.setOrg(org);

        teamProject = new Project();
        teamProject.setId("proj-team");
        teamProject.setName("Team Project");
        teamProject.setKey("TPROJ");
        teamProject.setOrgId("org-1");
        teamProject.setTeamId("team-1");
        teamProject.setVisibility(ProjectVisibility.TEAM);
        teamProject.setCreatedBy(adminUser);
        teamProject.setCreatedAt(OffsetDateTime.now());
        teamProject.setUpdatedAt(OffsetDateTime.now());

        projectWithNoTeam = new Project();
        projectWithNoTeam.setId("proj-no-team");
        projectWithNoTeam.setName("No Team Project");
        projectWithNoTeam.setKey("NTEAM");
        projectWithNoTeam.setOrgId("org-1");
        projectWithNoTeam.setTeamId(null);
        projectWithNoTeam.setVisibility(ProjectVisibility.ORG);
        projectWithNoTeam.setCreatedBy(adminUser);
        projectWithNoTeam.setCreatedAt(OffsetDateTime.now());
        projectWithNoTeam.setUpdatedAt(OffsetDateTime.now());

        adminMembership = new ProjectMember();
        adminMembership.setId("pm-admin");
        adminMembership.setProject(teamProject);
        adminMembership.setUser(adminUser);
        adminMembership.setRole(MemberRole.ADMIN);
        adminMembership.setJoinedAt(OffsetDateTime.now());
    }

    // --- canUserAccessProject: TEAM visibility ---

    @Test
    void teamVisibilityProject_isAccessibleToTeamMember() {
        TeamMember membership = new TeamMember();
        membership.setId("tm-1");
        membership.setTeam(team);
        membership.setUser(teamMemberUser);
        membership.setRole(TeamMember.TeamRole.MEMBER);
        membership.setJoinedAt(OffsetDateTime.now());

        when(projectMemberRepository.existsByProjectIdAndUserId("proj-team", "user-team-member")).thenReturn(false);
        when(teamMemberRepository.findByTeamIdAndUserId("team-1", "user-team-member")).thenReturn(Optional.of(membership));

        boolean result = projectService.canUserAccessProject("user-team-member", teamProject);

        assertThat(result).isTrue();
    }

    @Test
    void teamVisibilityProject_returns403ToOrgMemberNotInTeam() {
        when(projectRepository.findById("proj-team")).thenReturn(Optional.of(teamProject));
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-team", "user-org-only")).thenReturn(false);
        when(teamMemberRepository.findByTeamIdAndUserId("team-1", "user-org-only")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.getProject("proj-team", orgOnlyMemberUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void teamVisibilityProject_notAccessibleToUserNotInTeam() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-team", "user-org-only")).thenReturn(false);
        when(teamMemberRepository.findByTeamIdAndUserId("team-1", "user-org-only")).thenReturn(Optional.empty());

        boolean result = projectService.canUserAccessProject("user-org-only", teamProject);

        assertThat(result).isFalse();
    }

    // --- updateProject: TEAM visibility validation ---

    @Test
    void settingTeamVisibilityWithNoTeamId_returns400() {
        when(projectSecurityService.isProjectAdmin("proj-no-team", "user-admin")).thenReturn(true);
        when(projectRepository.findById("proj-no-team")).thenReturn(Optional.of(projectWithNoTeam));

        UpdateProjectRequest request = new UpdateProjectRequest()
                .visibility(UpdateProjectRequest.VisibilityEnum.TEAM);

        assertThatThrownBy(() -> projectService.updateProject("proj-no-team", request, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("teamId is required");
    }

    @Test
    void assigningTeamAndSettingTeamVisibility_succeeds() {
        when(projectSecurityService.isProjectAdmin("proj-no-team", "user-admin")).thenReturn(true);
        when(projectRepository.findById("proj-no-team")).thenReturn(Optional.of(projectWithNoTeam));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProjectRequest request = new UpdateProjectRequest()
                .teamId("team-1")
                .visibility(UpdateProjectRequest.VisibilityEnum.TEAM);

        ProjectResponse response = projectService.updateProject("proj-no-team", request, adminUser);

        assertThat(response).isNotNull();
        assertThat(projectWithNoTeam.getTeamId()).isEqualTo("team-1");
        assertThat(projectWithNoTeam.getVisibility()).isEqualTo(ProjectVisibility.TEAM);
    }

    @Test
    void assigningTeamId_doesNotRequireVisibilityChange() {
        when(projectSecurityService.isProjectAdmin("proj-no-team", "user-admin")).thenReturn(true);
        when(projectRepository.findById("proj-no-team")).thenReturn(Optional.of(projectWithNoTeam));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProjectRequest request = new UpdateProjectRequest()
                .teamId("team-1");

        ProjectResponse response = projectService.updateProject("proj-no-team", request, adminUser);

        assertThat(response).isNotNull();
        assertThat(projectWithNoTeam.getTeamId()).isEqualTo("team-1");
        assertThat(projectWithNoTeam.getVisibility()).isEqualTo(ProjectVisibility.ORG);
    }

    // --- getProject: explicit project member always has access regardless of team membership ---

    @Test
    void teamVisibilityProject_accessibleToExplicitProjectMember() {
        when(projectRepository.findById("proj-team")).thenReturn(Optional.of(teamProject));
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-team", "user-admin")).thenReturn(true);
        when(projectMemberRepository.findByProjectId("proj-team")).thenReturn(List.of(adminMembership));

        ProjectDetail detail = projectService.getProject("proj-team", adminUser);

        assertThat(detail).isNotNull();
        assertThat(detail.getId()).isEqualTo("proj-team");
    }
}
