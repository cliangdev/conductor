package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.ProjectRepository;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepositoryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectRepositoryService {

    private final ProjectRepositoryRepository projectRepositoryRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public ProjectRepositoryService(
            ProjectRepositoryRepository projectRepositoryRepository,
            ProjectMemberRepository projectMemberRepository) {
        this.projectRepositoryRepository = projectRepositoryRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    @Transactional
    public ProjectRepository addRepository(
            String projectId, String label, String repoUrl, String webhookSecret, String connectedByUserId) {
        verifyAdmin(projectId, connectedByUserId);

        ProjectRepository repo = new ProjectRepository();
        repo.setProjectId(projectId);
        repo.setLabel(label);
        repo.setRepoUrl(repoUrl);
        repo.setRepoFullName(parseRepoFullName(repoUrl));
        repo.setWebhookSecret(webhookSecret);
        repo.setConnectedBy(connectedByUserId);

        return projectRepositoryRepository.save(repo);
    }

    @Transactional(readOnly = true)
    public List<ProjectRepository> listRepositories(String projectId) {
        return projectRepositoryRepository.findByProjectId(projectId);
    }

    @Transactional
    public ProjectRepository updateRepository(
            String projectId, String repositoryId, String label, String webhookSecret, String callerUserId) {
        verifyAdmin(projectId, callerUserId);

        ProjectRepository repo = projectRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException("Repository not found: " + repositoryId));

        if (!repo.getProjectId().equals(projectId)) {
            throw new EntityNotFoundException("Repository not found: " + repositoryId);
        }

        if (label != null && !label.isBlank()) {
            repo.setLabel(label.trim());
        }
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            repo.setWebhookSecret(webhookSecret.trim());
        }

        return projectRepositoryRepository.save(repo);
    }

    @Transactional
    public void deleteRepository(String projectId, String repositoryId, String callerUserId) {
        verifyAdmin(projectId, callerUserId);

        ProjectRepository repo = projectRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException("Repository not found: " + repositoryId));

        if (!repo.getProjectId().equals(projectId)) {
            throw new EntityNotFoundException("Repository not found: " + repositoryId);
        }

        projectRepositoryRepository.delete(repo);
    }

    private void verifyAdmin(String projectId, String userId) {
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (member.getRole() != MemberRole.ADMIN) {
            throw new ForbiddenException("Only ADMIN can manage project repositories");
        }
    }

    String parseRepoFullName(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return "";
        }
        String normalized = repoUrl.trim();
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        String prefix = "https://github.com/";
        if (normalized.startsWith(prefix)) {
            return normalized.substring(prefix.length());
        }
        return normalized;
    }
}
