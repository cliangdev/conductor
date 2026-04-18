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
    private com.conductor.repository.OrgMemberRepository orgMemberRepository;

    @Mock
    private com.conductor.repository.TeamMemberRepository teamMemberRepository;

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
        testProject.setOrgId("org-abc");
        when(projectRepository.findProjectsByMemberUserId("creator-id")).thenReturn(List.of(testProject));
        when(orgMemberRepository.findByUserId("creator-id")).thenReturn(List.of());
        when(teamMemberRepository.findByUserId("creator-id")).thenReturn(List.of());
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "creator-id"))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.findByProjectId("proj-1")).thenReturn(List.of(adminMember));

        List<ProjectSummary> projects = projectService.listProjects(creator);

        assertThat(projects).hasSize(1);
        assertThat(projects.get(0).getId()).isEqualTo("proj-1");
        assertThat(projects.get(0).getRole()).isEqualTo("ADMIN");
        assertThat(projects.get(0).getMemberCount()).isEqualTo(1);
        // orgId is required so the frontend sidebar can sync active org from the URL's project
        assertThat(projects.get(0).getOrgId()).isEqualTo("org-abc");
    }

    @Test
    void getProjectReturnsForbiddenForNonMemberPrivateProject() {
        testProject.setOrgId("org-1");
        testProject.setVisibility(com.conductor.entity.ProjectVisibility.PRIVATE);

        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "creator-id")).thenReturn(false);

        assertThatThrownBy(() -> projectService.getProject("proj-1", creator))
                .isInstanceOf(com.conductor.exception.ForbiddenException.class);
    }

    @Test
    void updateMemberRoleThrowsAccessDeniedForNonAdmin() {
        when(projectSecurityService.isProjectAdmin("proj-1", "creator-id")).thenReturn(false);

        UpdateMemberRoleRequest request = new UpdateMemberRoleRequest("REVIEWER");

        assertThatThrownBy(() -> projectService.updateMemberRole("proj-1", "some-user", request, creator))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateLastAdminRoleThrowsBusinessException() {
        when(projectSecurityService.isProjectAdmin("proj-1", "creator-id")).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "creator-id"))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.countByProjectIdAndRole("proj-1", MemberRole.ADMIN)).thenReturn(1L);

        UpdateMemberRoleRequest request = new UpdateMemberRoleRequest("CREATOR");

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
        when(projectMemberRepository.countByProjectIdAndRole("proj-1", MemberRole.ADMIN)).thenReturn(2L);
        when(projectMemberRepository.save(any(ProjectMember.class))).thenReturn(adminMember);

        UpdateMemberRoleRequest request = new UpdateMemberRoleRequest("CREATOR");

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
        when(projectSecurityService.isProjectAdmin("proj-1", "creator-id")).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "creator-id"))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.countByProjectIdAndRole("proj-1", MemberRole.ADMIN)).thenReturn(1L);

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
}
