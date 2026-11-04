package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.generated.model.CreateProjectRequest;
import com.conductor.generated.model.MemberResponse;
import com.conductor.generated.model.ProjectResponse;
import com.conductor.generated.model.ProjectSummary;
import com.conductor.generated.model.UpdateMemberRoleRequest;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @Mock
    private WorkflowSeeder workflowSeeder;

    @InjectMocks
    private ProjectService projectService;

    private User creator;
    private Project testProject;
    private ProjectMember adminMember;

    @BeforeEach
    void setUp() {
        creator = new User();
        creator.setId("creator-id");
        creator.setEmail("creator@example.com");
        creator.setName("Creator");

        testProject = new Project();
        testProject.setId("proj-1");
        testProject.setName("Test Project");
        testProject.setKey("TEST");
        testProject.setDescription("A description");
        testProject.setCreatedBy(creator);
        testProject.setCreatedAt(OffsetDateTime.now());
        testProject.setUpdatedAt(OffsetDateTime.now());

        adminMember = new ProjectMember();
        adminMember.setId("member-1");
        adminMember.setProject(testProject);
        adminMember.setUser(creator);
        adminMember.setRole(MemberRole.ADMIN);
        adminMember.setJoinedAt(OffsetDateTime.now());
    }

    @Test
    void createProjectSavesProjectAndAdminMemberAtomically() {
        when(projectRepository.existsByKey(any())).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project p = invocation.getArgument(0);
            p.setId("proj-new");
            // simulate @PrePersist
            if (p.getCreatedAt() == null) p.setCreatedAt(OffsetDateTime.now());
            return p;
        });
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateProjectRequest request = new CreateProjectRequest("New Project").description("desc");
        ProjectResponse response = projectService.createProject(request, creator);

        // Verify project was saved
        verify(projectRepository).save(any(Project.class));

        // Verify member was saved with ADMIN role
        ArgumentCaptor<ProjectMember> memberCaptor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberRepository).save(memberCaptor.capture());
        ProjectMember savedMember = memberCaptor.getValue();
        assertThat(savedMember.getRole()).isEqualTo(MemberRole.ADMIN);
        assertThat(savedMember.getUser()).isEqualTo(creator);

        assertThat(response.getName()).isEqualTo("New Project");
        assertThat(response.getCreatedBy()).isEqualTo("creator-id");
    }

    @Test
    void listProjectsReturnsOnlyCallerProjects() {
        when(projectRepository.findProjectsByMemberUserId("creator-id")).thenReturn(List.of(testProject));
        adminMember.setProject(testProject);
        when(projectMemberRepository.findByUserId("creator-id")).thenReturn(List.of(adminMember));
        when(projectMemberRepository.countGroupedByProjectIds(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{"proj-1", 1L}));

        List<ProjectSummary> projects = projectService.listProjects(creator);

        assertThat(projects).hasSize(1);
        assertThat(projects.get(0).getId()).isEqualTo("proj-1");
        assertThat(projects.get(0).getRole()).isEqualTo("ADMIN");
        assertThat(projects.get(0).getMemberCount()).isEqualTo(1);
    }

    @Test
    void getProjectReturnsForbiddenForNonMember() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(projectSecurityService.isProjectMember("proj-1", "creator-id")).thenReturn(false);

        assertThatThrownBy(() -> projectService.getProject("proj-1", creator))
                .isInstanceOf(com.conductor.exception.ForbiddenException.class);
    }

    @Test
    void updateMemberRoleThrowsAccessDeniedForNonAdmin() {
        when(projectSecurityService.isProjectAdmin("proj-1", "creator-id")).thenReturn(false);

        UpdateMemberRoleRequest request = new UpdateMemberRoleRequest().role("REVIEWER");

        assertThatThrownBy(() -> projectService.updateMemberRole("proj-1", "some-user", request, creator))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateLastAdminRoleThrowsBusinessException() {
        when(projectSecurityService.isProjectAdmin("proj-1", "creator-id")).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "creator-id"))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.countAdmins("proj-1")).thenReturn(1L);

        UpdateMemberRoleRequest request = new UpdateMemberRoleRequest().role("CREATOR");

        assertThatThrownBy(() -> projectService.updateMemberRole("proj-1", "creator-id", request, creator))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Cannot remove the last project admin");
    }

    @Test
    void updateMemberRoleSucceedsWhenMultipleAdmins() {
        User secondAdmin = new User();
        secondAdmin.setId("admin-2");
        secondAdmin.setEmail("admin2@example.com");
        secondAdmin.setName("Admin 2");

        ProjectMember adminMember2 = new ProjectMember();
        adminMember2.setId("member-2");
        adminMember2.setProject(testProject);
        adminMember2.setUser(creator);
        adminMember2.setRole(MemberRole.ADMIN);
        adminMember2.setJoinedAt(OffsetDateTime.now());

        when(projectSecurityService.isProjectAdmin("proj-1", "creator-id")).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "creator-id"))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.countAdmins("proj-1")).thenReturn(2L);
        when(projectMemberRepository.save(any(ProjectMember.class))).thenReturn(adminMember);

        UpdateMemberRoleRequest request = new UpdateMemberRoleRequest().role("CREATOR");

        MemberResponse response = projectService.updateMemberRole("proj-1", "creator-id", request, creator);

        verify(projectMemberRepository).save(adminMember);
        assertThat(adminMember.getRole()).isEqualTo(MemberRole.CREATOR);
    }

    @Test
    void removeMemberThrowsAccessDeniedForNonAdmin() {
        when(projectSecurityService.isProjectAdmin("proj-1", "creator-id")).thenReturn(false);

        assertThatThrownBy(() -> projectService.removeMember("proj-1", "some-user", creator))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void removeLastAdminThrowsBusinessException() {
        // A member removing themselves (leave) still cannot drop the last admin.
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "creator-id"))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.countAdmins("proj-1")).thenReturn(1L);

        assertThatThrownBy(() -> projectService.removeMember("proj-1", "creator-id", creator))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Cannot remove the last project admin");
    }

    @Test
    void removeMemberSucceedsForNonLastAdmin() {
        User member2 = new User();
        member2.setId("member-user-2");
        member2.setEmail("member2@example.com");

        ProjectMember nonAdminMember = new ProjectMember();
        nonAdminMember.setId("pm-2");
        nonAdminMember.setProject(testProject);
        nonAdminMember.setUser(member2);
        nonAdminMember.setRole(MemberRole.CREATOR);
        nonAdminMember.setJoinedAt(OffsetDateTime.now());

        when(projectSecurityService.isProjectAdmin("proj-1", "creator-id")).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "member-user-2"))
                .thenReturn(Optional.of(nonAdminMember));

        projectService.removeMember("proj-1", "member-user-2", creator);

        verify(projectMemberRepository).delete(nonAdminMember);
    }

    @Test
    void resolveRequestedRolesAcceptsRolesOrLegacyRoleButNotBothOrNeither() {
        assertThat(ProjectService.resolveRequestedRoles(java.util.List.of("CREATOR", "REVIEWER"), null))
                .containsExactlyInAnyOrder(MemberRole.CREATOR, MemberRole.REVIEWER);
        assertThat(ProjectService.resolveRequestedRoles(null, "reviewer")).containsExactly(MemberRole.REVIEWER);
        assertThat(ProjectService.resolveRequestedRoles(java.util.List.of(), null)).isEmpty();

        assertThatThrownBy(() -> ProjectService.resolveRequestedRoles(java.util.List.of("CREATOR"), "CREATOR"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ProjectService.resolveRequestedRoles(null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ProjectService.resolveRequestedRoles(null, "OWNER"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Invalid role");
    }

    @Test
    void demotingTheLastAdminToTwoNonAdminRolesIsRefused() {
        when(projectSecurityService.isProjectAdmin("proj-1", "creator-id")).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "creator-id"))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.countAdmins("proj-1")).thenReturn(1L);

        UpdateMemberRoleRequest request = new UpdateMemberRoleRequest()
                .roles(java.util.List.of(com.conductor.generated.model.MemberRoleName.CREATOR,
                        com.conductor.generated.model.MemberRoleName.REVIEWER));

        assertThatThrownBy(() -> projectService.updateMemberRole("proj-1", "creator-id", request, creator))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Cannot remove the last project admin");
    }

    @Test
    void theLastAdminMayKeepAdminWhileGainingOtherRoles() {
        when(projectSecurityService.isProjectAdmin("proj-1", "creator-id")).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "creator-id"))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.save(any(ProjectMember.class))).thenReturn(adminMember);

        MemberResponse response = projectService.updateMemberRole("proj-1", "creator-id",
                new UpdateMemberRoleRequest().roles(java.util.List.of(
                        com.conductor.generated.model.MemberRoleName.ADMIN,
                        com.conductor.generated.model.MemberRoleName.REVIEWER)), creator);

        assertThat(adminMember.getRole()).isEqualTo(MemberRole.ADMIN);
        assertThat(response.getRoles()).hasSize(2);
        verify(projectMemberRepository, never()).countAdmins(any());
    }

    @Test
    void emptyRolesMakeTheMemberAReadOnlyViewerWithLegacyRoleReviewer() {
        User other = new User();
        other.setId("other");
        ProjectMember member = new ProjectMember();
        member.setProject(testProject);
        member.setUser(other);
        member.setRoles(java.util.Set.of(MemberRole.CREATOR));
        member.setJoinedAt(OffsetDateTime.now());
        when(projectSecurityService.isProjectAdmin("proj-1", "creator-id")).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "other")).thenReturn(Optional.of(member));
        when(projectMemberRepository.save(any(ProjectMember.class))).thenReturn(member);

        MemberResponse response = projectService.updateMemberRole("proj-1", "other",
                new UpdateMemberRoleRequest().roles(java.util.List.of()), creator);

        assertThat(member.getRoles()).isEmpty();
        assertThat(response.getRole()).isEqualTo("REVIEWER");
        assertThat(response.getRoles()).isEmpty();
    }
}
