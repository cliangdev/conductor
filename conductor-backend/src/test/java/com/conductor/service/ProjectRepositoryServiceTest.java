package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.ProjectRepository;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepositoryRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectRepositoryServiceTest {

    @Mock
    private ProjectRepositoryRepository projectRepositoryRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @InjectMocks
    private ProjectRepositoryService projectRepositoryService;

    private static final String PROJECT_ID = "proj-1";
    private static final String ADMIN_USER_ID = "admin-1";
    private static final String REVIEWER_USER_ID = "reviewer-1";
    private static final String REPO_URL = "https://github.com/owner/repo";

    private ProjectMember adminMember;
    private ProjectMember reviewerMember;

    @BeforeEach
    void setUp() {
        Project project = new Project();
        project.setId(PROJECT_ID);

        adminMember = new ProjectMember();
        adminMember.setRole(MemberRole.ADMIN);

        reviewerMember = new ProjectMember();
        reviewerMember.setRole(MemberRole.REVIEWER);
    }

    // --- addRepository tests ---

    @Test
    void addRepositoryHappyPathSavesAndReturnsEntity() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, ADMIN_USER_ID))
                .thenReturn(Optional.of(adminMember));
        when(projectRepositoryRepository.save(any(ProjectRepository.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectRepository result = projectRepositoryService.addRepository(
                PROJECT_ID, "My Repo", REPO_URL, "secret123", ADMIN_USER_ID);

        ArgumentCaptor<ProjectRepository> captor = ArgumentCaptor.forClass(ProjectRepository.class);
        verify(projectRepositoryRepository).save(captor.capture());

        ProjectRepository saved = captor.getValue();
        assertThat(saved.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(saved.getLabel()).isEqualTo("My Repo");
        assertThat(saved.getRepoUrl()).isEqualTo(REPO_URL);
        assertThat(saved.getRepoFullName()).isEqualTo("owner/repo");
        assertThat(saved.getWebhookSecret()).isEqualTo("secret123");
        assertThat(saved.getConnectedBy()).isEqualTo(ADMIN_USER_ID);
    }

    @Test
    void addRepositoryNonAdminThrowsForbidden() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, REVIEWER_USER_ID))
                .thenReturn(Optional.of(reviewerMember));

        assertThatThrownBy(() -> projectRepositoryService.addRepository(
                PROJECT_ID, "My Repo", REPO_URL, "secret", REVIEWER_USER_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only ADMIN can manage project repositories");

        verify(projectRepositoryRepository, never()).save(any());
    }

    @Test
    void addRepositoryUnknownUserThrowsNotFound() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "unknown"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectRepositoryService.addRepository(
                PROJECT_ID, "My Repo", REPO_URL, "secret", "unknown"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // --- listRepositories tests ---

    @Test
    void listRepositoriesReturnsAllForProject() {
        ProjectRepository r1 = repoWithId("r1", PROJECT_ID);
        ProjectRepository r2 = repoWithId("r2", PROJECT_ID);
        when(projectRepositoryRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(r1, r2));

        List<ProjectRepository> result = projectRepositoryService.listRepositories(PROJECT_ID);

        assertThat(result).hasSize(2).containsExactly(r1, r2);
    }

    @Test
    void listRepositoriesReturnsEmptyListWhenNone() {
        when(projectRepositoryRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());

        List<ProjectRepository> result = projectRepositoryService.listRepositories(PROJECT_ID);

        assertThat(result).isEmpty();
    }

    // --- deleteRepository tests ---

    @Test
    void deleteRepositoryHappyPathDeletesEntity() {
        ProjectRepository repo = repoWithId("repo-1", PROJECT_ID);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, ADMIN_USER_ID))
                .thenReturn(Optional.of(adminMember));
        when(projectRepositoryRepository.findById("repo-1")).thenReturn(Optional.of(repo));

        projectRepositoryService.deleteRepository(PROJECT_ID, "repo-1", ADMIN_USER_ID);

        verify(projectRepositoryRepository).delete(repo);
    }

    @Test
    void deleteRepositoryNotFoundThrowsEntityNotFoundException() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, ADMIN_USER_ID))
                .thenReturn(Optional.of(adminMember));
        when(projectRepositoryRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectRepositoryService.deleteRepository(PROJECT_ID, "missing", ADMIN_USER_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Repository not found");
    }

    @Test
    void deleteRepositoryWrongProjectThrowsEntityNotFoundException() {
        ProjectRepository repo = repoWithId("repo-1", "other-project");
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, ADMIN_USER_ID))
                .thenReturn(Optional.of(adminMember));
        when(projectRepositoryRepository.findById("repo-1")).thenReturn(Optional.of(repo));

        assertThatThrownBy(() -> projectRepositoryService.deleteRepository(PROJECT_ID, "repo-1", ADMIN_USER_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Repository not found");

        verify(projectRepositoryRepository, never()).delete(any());
    }

    @Test
    void deleteRepositoryNonAdminThrowsForbidden() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, REVIEWER_USER_ID))
                .thenReturn(Optional.of(reviewerMember));

        assertThatThrownBy(() -> projectRepositoryService.deleteRepository(PROJECT_ID, "repo-1", REVIEWER_USER_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only ADMIN can manage project repositories");

        verify(projectRepositoryRepository, never()).delete(any());
    }

    // --- parseRepoFullName tests ---

    @Test
    void parseRepoFullNameStripsGitHubPrefixAndGitSuffix() {
        assertThat(projectRepositoryService.parseRepoFullName("https://github.com/owner/repo.git"))
                .isEqualTo("owner/repo");
    }

    @Test
    void parseRepoFullNameHandlesUrlWithoutGitSuffix() {
        assertThat(projectRepositoryService.parseRepoFullName("https://github.com/owner/repo"))
                .isEqualTo("owner/repo");
    }

    @Test
    void parseRepoFullNameHandlesNonGitHubUrl() {
        assertThat(projectRepositoryService.parseRepoFullName("https://gitlab.com/owner/repo"))
                .isEqualTo("https://gitlab.com/owner/repo");
    }

    @Test
    void parseRepoFullNameHandlesNullUrl() {
        assertThat(projectRepositoryService.parseRepoFullName(null)).isEqualTo("");
    }

    private ProjectRepository repoWithId(String id, String projectId) {
        ProjectRepository repo = new ProjectRepository();
        repo.setId(id);
        repo.setProjectId(projectId);
        repo.setLabel("Test Repo");
        repo.setRepoUrl(REPO_URL);
        repo.setRepoFullName("owner/repo");
        repo.setWebhookSecret("secret");
        repo.setConnectedBy(ADMIN_USER_ID);
        repo.setConnectedAt(OffsetDateTime.now());
        return repo;
    }
}
