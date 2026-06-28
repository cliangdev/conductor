package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.CreateProjectRequest;
import com.conductor.generated.model.MemberResponse;
import com.conductor.generated.model.ProjectDetail;
import com.conductor.generated.model.ProjectResponse;
import com.conductor.generated.model.ProjectSummary;
import com.conductor.generated.model.UpdateMemberRoleRequest;
import com.conductor.generated.model.UpdateProjectRequest;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Workspace (Project) business logic. A Project is the single top-level
 * container ("Workspace" in the UI); membership in {@link ProjectMember} is the
 * sole access gate.
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowSeeder workflowSeeder;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            WorkflowSeeder workflowSeeder) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectSecurityService = projectSecurityService;
        this.workflowSeeder = workflowSeeder;
    }

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request, User creator) {
        Project project = createWorkspace(request.getName(), request.getDescription(), creator);
        return toProjectResponse(project);
    }

    /**
     * Ensures the user has at least one workspace; called at signup so a new
     * user always lands inside a working workspace. Idempotent.
     */
    @Transactional
    public void ensureDefaultWorkspace(User user) {
        if (!projectMemberRepository.findByUserId(user.getId()).isEmpty()) {
            return;
        }
        String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getName();
        String name = (displayName != null && !displayName.isBlank())
                ? displayName + "'s Workspace"
                : "My Workspace";
        createWorkspace(name, null, user);
    }

    private Project createWorkspace(String name, String description, User creator) {
        Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        project.setCreatedBy(creator);
        project.setKey(resolveUniqueKey(name));
        projectRepository.save(project);

        // Every workspace starts with the built-in ENGINEERING lifecycle Workflow so its Work Items
        // (Issues) render and the sidebar has a nav entry from day one. (COND-22)
        workflowSeeder.seedEngineering(project);

        ProjectMember adminMember = new ProjectMember();
        adminMember.setProject(project);
        adminMember.setUser(creator);
        adminMember.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(adminMember);

        return project;
    }

    @Transactional(readOnly = true)
    public List<ProjectSummary> listProjects(User caller) {
        return projectRepository.findProjectsByMemberUserId(caller.getId()).stream()
                .map(project -> {
                    String roleStr = projectMemberRepository
                            .findByProjectIdAndUserId(project.getId(), caller.getId())
                            .map(m -> m.getRole().name())
                            .orElse(null);
                    long memberCount = projectMemberRepository.findByProjectId(project.getId()).size();
                    return toProjectSummary(project, roleStr, (int) memberCount);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectDetail getProject(String projectId, User caller) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new ForbiddenException("You do not have access to this project");
        }

        long memberCount = projectMemberRepository.findByProjectId(projectId).size();

        return toProjectDetail(project, (int) memberCount);
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(String projectId, User caller) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new ForbiddenException("You do not have access to this project");
        }

        return projectMemberRepository.findByProjectId(projectId).stream()
                .map(this::toMemberResponse)
                .toList();
    }

    @Transactional
    public ProjectResponse updateProject(String projectId, UpdateProjectRequest request, User caller) {
        if (!projectSecurityService.isProjectAdmin(projectId, caller.getId())) {
            throw new AccessDeniedException("Only project admins can update project settings");
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (request.getName() != null) {
            project.setName(request.getName());
        }
        if (request.getDescription() != null) {
            project.setDescription(request.getDescription());
        }

        projectRepository.save(project);
        return toProjectResponse(project);
    }

    @Transactional
    public MemberResponse updateMemberRole(String projectId, String userId, UpdateMemberRoleRequest request, User caller) {
        if (!projectSecurityService.isProjectAdmin(projectId, caller.getId())) {
            throw new AccessDeniedException("Only project admins can update member roles");
        }

        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Member not found"));

        MemberRole newRole = parseMemberRole(request.getRole());

        if (member.getRole() == MemberRole.ADMIN && newRole != MemberRole.ADMIN) {
            assertNotLastAdmin(projectId);
        }

        member.setRole(newRole);
        projectMemberRepository.save(member);

        return toMemberResponse(member);
    }

    @Transactional
    public void removeMember(String projectId, String userId, User caller) {
        // Members may remove themselves (leave workspace); otherwise admin only.
        boolean isSelf = userId.equals(caller.getId());
        if (!isSelf && !projectSecurityService.isProjectAdmin(projectId, caller.getId())) {
            throw new AccessDeniedException("Only project admins can remove members");
        }

        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Member not found"));

        if (member.getRole() == MemberRole.ADMIN) {
            assertNotLastAdmin(projectId);
        }

        projectMemberRepository.delete(member);
    }

    /**
     * Guards the hard invariant that a workspace always retains at least one
     * admin (there is no org-level fallback). Applies to both role changes and
     * member removal, including a member leaving.
     */
    private void assertNotLastAdmin(String projectId) {
        long adminCount = projectMemberRepository.countByProjectIdAndRole(projectId, MemberRole.ADMIN);
        if (adminCount <= 1) {
            throw new BusinessException("Cannot remove the last project admin");
        }
    }

    private MemberRole parseMemberRole(String role) {
        try {
            return MemberRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid role: " + role);
        }
    }

    String deriveKey(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9\\s]", "").trim();
        String[] words = cleaned.split("\\s+");
        String candidate;
        if (words.length > 1) {
            candidate = Arrays.stream(words)
                .filter(w -> !w.isEmpty())
                .map(w -> String.valueOf(w.charAt(0)).toUpperCase())
                .collect(Collectors.joining());
            if (candidate.length() > 6) candidate = candidate.substring(0, 6);
        } else {
            String word = cleaned.toUpperCase().replaceAll("\\s", "");
            candidate = word.length() > 4 ? word.substring(0, 4) : word;
        }
        if (candidate.length() < 2) candidate = (candidate + "XX").substring(0, 2);
        return candidate;
    }

    String resolveUniqueKey(String name) {
        String base = deriveKey(name);
        if (!projectRepository.existsByKey(base)) return base;
        for (int i = 1; i <= 99; i++) {
            String variant = base + i;
            if (!projectRepository.existsByKey(variant)) return variant;
        }
        throw new BusinessException("Cannot derive unique key for project: " + name);
    }

    private ProjectResponse toProjectResponse(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getKey(),
                project.getCreatedBy().getId(),
                project.getCreatedAt())
                .description(project.getDescription());
    }

    private ProjectSummary toProjectSummary(Project project, String role, int memberCount) {
        return new ProjectSummary(
                project.getId(),
                project.getName(),
                project.getKey(),
                memberCount,
                project.getCreatedAt())
                .description(project.getDescription())
                .role(role);
    }

    private ProjectDetail toProjectDetail(Project project, int memberCount) {
        return new ProjectDetail(
                project.getId(),
                project.getName(),
                project.getKey(),
                project.getCreatedBy().getId(),
                memberCount,
                project.getCreatedAt())
                .description(project.getDescription());
    }

    private MemberResponse toMemberResponse(ProjectMember member) {
        User user = member.getUser();
        return new MemberResponse()
                .userId(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .role(member.getRole().name())
                .joinedAt(member.getJoinedAt());
    }
}
