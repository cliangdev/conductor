package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.OrgMember;
import com.conductor.entity.Organization;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.ProjectVisibility;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.CreateProjectRequest;
import com.conductor.generated.model.ProjectResponse;
import com.conductor.generated.model.ProjectSummary;
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.TeamMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectCreationTest {

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

    private User creator;
    private Organization org;
    private OrgMember orgMembership;

    @BeforeEach
    void setUp() {
        creator = new User();
        creator.setId("creator-id");
        creator.setEmail("creator@example.com");
        creator.setName("Creator");

        org = new Organization();
        org.setId("org-1");
        org.setName("Test Org");
        org.setSlug("test-org");

        orgMembership = new OrgMember();
        orgMembership.setId("om-1");
        orgMembership.setOrg(org);
        orgMembership.setUser(creator);
        orgMembership.setRole(OrgMember.OrgRole.MEMBER);
        orgMembership.setJoinedAt(OffsetDateTime.now());
    }

    private void stubSave() {
        when(projectRepository.existsByKey(any())).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId("proj-new");
            if (p.getCreatedAt() == null) p.setCreatedAt(OffsetDateTime.now());
            return p;
        });
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createProjectAutoAssignsOrgIdWhenUserHasExactlyOneOrg() {
        stubSave();
        when(orgMemberRepository.findByUserId("creator-id")).thenReturn(List.of(orgMembership));

        CreateProjectRequest request = new CreateProjectRequest("My Project");
        ProjectResponse response = projectService.createProject(request, creator);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertThat(captor.getValue().getOrgId()).isEqualTo("org-1");
    }

    @Test
    void createProjectLeavesOrgIdNullWhenUserHasNoOrgs() {
        stubSave();
        when(orgMemberRepository.findByUserId("creator-id")).thenReturn(List.of());

        CreateProjectRequest request = new CreateProjectRequest("My Project");
        projectService.createProject(request, creator);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertThat(captor.getValue().getOrgId()).isNull();
    }

    @Test
    void createProjectLeavesOrgIdNullWhenUserHasMultipleOrgs() {
        stubSave();
        Organization org2 = new Organization();
        org2.setId("org-2");
        org2.setName("Other Org");
        org2.setSlug("other-org");

        OrgMember membership2 = new OrgMember();
        membership2.setId("om-2");
        membership2.setOrg(org2);
        membership2.setUser(creator);
        membership2.setRole(OrgMember.OrgRole.MEMBER);
        membership2.setJoinedAt(OffsetDateTime.now());

        when(orgMemberRepository.findByUserId("creator-id")).thenReturn(List.of(orgMembership, membership2));

        CreateProjectRequest request = new CreateProjectRequest("My Project");
        projectService.createProject(request, creator);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertThat(captor.getValue().getOrgId()).isNull();
    }

    @Test
    void createProjectDefaultsToOrgVisibility() {
        stubSave();
        when(orgMemberRepository.findByUserId("creator-id")).thenReturn(List.of());

        CreateProjectRequest request = new CreateProjectRequest("My Project");
        projectService.createProject(request, creator);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertThat(captor.getValue().getVisibility()).isEqualTo(ProjectVisibility.ORG);
    }

    @Test
    void createProjectUsesExplicitOrgIdWhenProvided() {
        stubSave();
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "creator-id"))
                .thenReturn(Optional.of(orgMembership));

        CreateProjectRequest request = new CreateProjectRequest("My Project").orgId("org-1");
        projectService.createProject(request, creator);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertThat(captor.getValue().getOrgId()).isEqualTo("org-1");
    }

    @Test
    void createProjectThrowsForbiddenWhenExplicitOrgIdAndNotMember() {
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "creator-id"))
                .thenReturn(Optional.empty());

        CreateProjectRequest request = new CreateProjectRequest("My Project").orgId("org-1");
        assertThatThrownBy(() -> projectService.createProject(request, creator))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void listOrgProjectsReturnsOnlyVisibleProjects() {
        Project orgProject = new Project();
        orgProject.setId("proj-org");
        orgProject.setName("Org Project");
        orgProject.setKey("ORG");
        orgProject.setOrgId("org-1");
        orgProject.setVisibility(ProjectVisibility.ORG);
        orgProject.setCreatedBy(creator);
        orgProject.setCreatedAt(OffsetDateTime.now());

        Project privateProject = new Project();
        privateProject.setId("proj-priv");
        privateProject.setName("Private Project");
        privateProject.setKey("PRIV");
        privateProject.setOrgId("org-1");
        privateProject.setVisibility(ProjectVisibility.PRIVATE);
        privateProject.setCreatedBy(creator);
        privateProject.setCreatedAt(OffsetDateTime.now());

        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "creator-id"))
                .thenReturn(Optional.of(orgMembership));
        when(projectRepository.findByOrgId("org-1")).thenReturn(List.of(orgProject, privateProject));

        // For orgProject (ORG visibility): not explicit member, but IS org member
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-org", "creator-id")).thenReturn(false);
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "creator-id"))
                .thenReturn(Optional.of(orgMembership));

        // For privateProject (PRIVATE visibility): not explicit member
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-priv", "creator-id")).thenReturn(false);

        when(projectMemberRepository.findByProjectIdAndUserId("proj-org", "creator-id"))
                .thenReturn(Optional.empty());
        when(projectMemberRepository.findByProjectId("proj-org")).thenReturn(List.of());

        List<ProjectSummary> result = projectService.listOrgProjects("org-1", creator);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("proj-org");
    }

    @Test
    void listOrgProjectsThrowsForbiddenWhenNotOrgMember() {
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "creator-id"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.listOrgProjects("org-1", creator))
                .isInstanceOf(ForbiddenException.class);
    }
}
