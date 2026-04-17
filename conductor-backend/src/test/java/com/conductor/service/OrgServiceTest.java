package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Organization;
import com.conductor.entity.OrgMember;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.OrgRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrgServiceTest {

    @Mock
    private OrgRepository orgRepository;

    @Mock
    private OrgMemberRepository orgMemberRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OrgService orgService;

    private User creator;
    private Organization testOrg;

    @BeforeEach
    void setUp() {
        creator = new User();
        creator.setId("user-1");
        creator.setEmail("user@example.com");
        creator.setName("Test User");

        testOrg = new Organization();
        testOrg.setId("org-1");
        testOrg.setName("My Org");
        testOrg.setSlug("my-org");
        testOrg.setCreatedBy(creator);
        testOrg.setCreatedAt(OffsetDateTime.now());
        testOrg.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void createOrg_savesOrgAndAdminMembership() {
        when(orgRepository.findBySlug("my-org")).thenReturn(Optional.empty());
        when(orgMemberRepository.findByUserId("user-1")).thenReturn(List.of());
        when(userRepository.findById("user-1")).thenReturn(Optional.of(creator));
        when(projectMemberRepository.findByUserIdAndRole("user-1", MemberRole.ADMIN)).thenReturn(List.of());
        when(orgRepository.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0);
            o.setId("org-new");
            o.setCreatedAt(OffsetDateTime.now());
            o.setUpdatedAt(OffsetDateTime.now());
            return o;
        });

        Organization result = orgService.createOrg("user-1", "My Org", "my-org");

        assertThat(result).isNotNull();

        ArgumentCaptor<OrgMember> memberCaptor = ArgumentCaptor.forClass(OrgMember.class);
        verify(orgMemberRepository).save(memberCaptor.capture());
        OrgMember saved = memberCaptor.getValue();
        assertThat(saved.getRole()).isEqualTo(OrgMember.OrgRole.ADMIN);
    }

    @Test
    void createOrg_throwsConflictWhenSlugTaken() {
        when(orgRepository.findBySlug("my-org")).thenReturn(Optional.of(testOrg));

        assertThatThrownBy(() -> orgService.createOrg("user-1", "My Org", "my-org"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("my-org");
    }

    @Test
    void createOrg_migratesExistingProjectsOnFirstOrg() {
        when(orgRepository.findBySlug("my-org")).thenReturn(Optional.empty());
        when(orgMemberRepository.findByUserId("user-1")).thenReturn(List.of());
        when(userRepository.findById("user-1")).thenReturn(Optional.of(creator));
        when(orgRepository.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0);
            o.setId("org-new");
            o.setCreatedAt(OffsetDateTime.now());
            o.setUpdatedAt(OffsetDateTime.now());
            return o;
        });

        Project unmigrated = new Project();
        unmigrated.setId("proj-1");
        unmigrated.setOrgId(null);

        ProjectMember adminMembership = new ProjectMember();
        adminMembership.setProject(unmigrated);
        adminMembership.setRole(MemberRole.ADMIN);

        when(projectMemberRepository.findByUserIdAndRole("user-1", MemberRole.ADMIN))
                .thenReturn(List.of(adminMembership));

        orgService.createOrg("user-1", "My Org", "my-org");

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(projectCaptor.capture());
        assertThat(projectCaptor.getValue().getOrgId()).isEqualTo("org-new");
    }

    @Test
    void createOrg_doesNotMigrateProjectsWhenUserAlreadyHasOrg() {
        when(orgRepository.findBySlug("my-org")).thenReturn(Optional.empty());

        OrgMember existingMembership = new OrgMember();
        existingMembership.setOrg(testOrg);
        when(orgMemberRepository.findByUserId("user-1")).thenReturn(List.of(existingMembership));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(creator));
        when(orgRepository.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0);
            o.setId("org-new");
            o.setCreatedAt(OffsetDateTime.now());
            o.setUpdatedAt(OffsetDateTime.now());
            return o;
        });

        orgService.createOrg("user-1", "My Org", "my-org");

        verify(projectMemberRepository, never()).findByUserIdAndRole(any(), any());
        verify(projectRepository, never()).save(any());
    }

    @Test
    void getOrg_returnsOrgForMember() {
        OrgMember membership = new OrgMember();
        membership.setOrg(testOrg);

        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-1"))
                .thenReturn(Optional.of(membership));

        Organization result = orgService.getOrg("org-1", "user-1");

        assertThat(result.getId()).isEqualTo("org-1");
    }

    @Test
    void getOrg_throwsNotFoundWhenOrgMissing() {
        when(orgRepository.findById("org-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orgService.getOrg("org-missing", "user-1"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getOrg_throwsForbiddenWhenNotMember() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> orgService.getOrg("org-1", "user-1"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getOrgsForUser_returnsAllOrgs() {
        OrgMember m1 = new OrgMember();
        m1.setOrg(testOrg);

        Organization org2 = new Organization();
        org2.setId("org-2");
        OrgMember m2 = new OrgMember();
        m2.setOrg(org2);

        when(orgMemberRepository.findByUserId("user-1")).thenReturn(List.of(m1, m2));

        List<Organization> result = orgService.getOrgsForUser("user-1");

        assertThat(result).hasSize(2);
    }

    @Test
    void getOrCreatePersonalOrg_createsOrgWhenNoneExists() {
        when(orgMemberRepository.findByUserId("user-1")).thenReturn(List.of());
        when(orgRepository.findBySlug(any())).thenReturn(Optional.empty());
        when(userRepository.findById("user-1")).thenReturn(Optional.of(creator));
        when(orgRepository.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0);
            o.setId("personal-org");
            o.setCreatedAt(OffsetDateTime.now());
            o.setUpdatedAt(OffsetDateTime.now());
            return o;
        });
        when(projectMemberRepository.findByUserIdAndRole("user-1", MemberRole.ADMIN))
                .thenReturn(List.of());

        Organization result = orgService.getOrCreatePersonalOrg("user-1", "Alice Smith", "alice@example.com");

        assertThat(result).isNotNull();
        verify(orgRepository).save(any());
        verify(orgMemberRepository).save(any(OrgMember.class));
    }

    @Test
    void getOrCreatePersonalOrg_returnsExistingOrgWhenAlreadyMember() {
        OrgMember existingMembership = new OrgMember();
        existingMembership.setOrg(testOrg);
        when(orgMemberRepository.findByUserId("user-1")).thenReturn(List.of(existingMembership));

        Organization result = orgService.getOrCreatePersonalOrg("user-1", "Test User", "user@example.com");

        assertThat(result.getId()).isEqualTo("org-1");
        verify(orgRepository, never()).save(any());
    }

    @Test
    void migrateExistingProjects_updatesProjectsWithNullOrgId() {
        Project proj1 = new Project();
        proj1.setId("p1");
        proj1.setOrgId(null);

        Project proj2 = new Project();
        proj2.setId("p2");
        proj2.setOrgId("already-set");

        ProjectMember m1 = new ProjectMember();
        m1.setProject(proj1);
        m1.setRole(MemberRole.ADMIN);

        ProjectMember m2 = new ProjectMember();
        m2.setProject(proj2);
        m2.setRole(MemberRole.ADMIN);

        when(projectMemberRepository.findByUserIdAndRole("user-1", MemberRole.ADMIN))
                .thenReturn(List.of(m1, m2));

        orgService.migrateExistingProjects("user-1", "org-new");

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("p1");
        assertThat(captor.getValue().getOrgId()).isEqualTo("org-new");
    }
}
