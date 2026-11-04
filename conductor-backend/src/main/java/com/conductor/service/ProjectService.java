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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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

        // Every workspace starts with the built-in lifecycle Workflows so its Work Items render and the
        // sidebar has nav entries from day one: ENGINEERING Issues (COND-22) and MARKETING Posts (COND-23).
        workflowSeeder.seedEngineering(project);
        workflowSeeder.seedMarketing(project);
        workflowSeeder.seedMarketingChanges(project);
        workflowSeeder.seedTask(project);

        ProjectMember adminMember = new ProjectMember();
        adminMember.setProject(project);
        adminMember.setUser(creator);
        adminMember.setRoles(Set.of(MemberRole.ADMIN));
        projectMemberRepository.save(adminMember);

        return project;
    }

    @Transactional(readOnly = true)
    public List<ProjectSummary> listProjects(User caller) {
        List<Project> projects = projectRepository.findProjectsByMemberUserId(caller.getId());
        if (projects.isEmpty()) {
            return List.of();
        }
        // The caller's role in every project from one read of their memberships, and every member count
        // from one grouped count: three queries in total, however many workspaces they belong to.
        Map<String, ProjectMember> memberByProject = new HashMap<>();
        for (ProjectMember m : projectMemberRepository.findByUserId(caller.getId())) {
            memberByProject.put(m.getProject().getId(), m);
        }
        Map<String, Long> countByProject = new HashMap<>();
        for (Object[] row : projectMemberRepository.countGroupedByProjectIds(
                projects.stream().map(Project::getId).toList())) {
            countByProject.put((String) row[0], (Long) row[1]);
        }
        return projects.stream()
                .map(project -> toProjectSummary(project, memberByProject.get(project.getId()),
                        countByProject.getOrDefault(project.getId(), 0L).intValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectDetail getProject(String projectId, User caller) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new ForbiddenException("You do not have access to this project");
        }

        long memberCount = projectMemberRepository.countByProjectId(projectId);

        return toProjectDetail(project, (int) memberCount);
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(String projectId, User caller) {
        if (!projectRepository.existsById(projectId)) {
            throw new EntityNotFoundException("Project not found");
        }

        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new ForbiddenException("You do not have access to this project");
        }

        return projectMemberRepository.findByProjectIdWithUser(projectId).stream()
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

        Set<MemberRole> newRoles = resolveRequestedRoles(request.getRoles(), request.getRole());

        if (member.getRoles().contains(MemberRole.ADMIN) && !newRoles.contains(MemberRole.ADMIN)) {
            assertNotLastAdmin(projectId);
        }

        member.setRoles(newRoles);
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

        if (member.getRoles().contains(MemberRole.ADMIN)) {
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
        long adminCount = projectMemberRepository.countAdmins(projectId);
        if (adminCount <= 1) {
            throw new BusinessException("Cannot remove the last project admin");
        }
    }

    /**
     * The role set a request asks for: {@code roles} (possibly empty, meaning a read-only viewer) or the
     * legacy single {@code role}. Sending both, or neither, is a 400.
     */
    static Set<MemberRole> resolveRequestedRoles(java.util.List<?> roles, String legacyRole) {
        if (roles != null && legacyRole != null) {
            throw new BusinessException("Send either 'roles' or 'role', not both");
        }
        if (roles == null && legacyRole == null) {
            throw new BusinessException("'roles' is required");
        }
        java.util.List<?> names = roles != null ? roles : java.util.List.of(legacyRole);
        Set<MemberRole> result = java.util.EnumSet.noneOf(MemberRole.class);
        for (Object name : names) {
            try {
                result.add(MemberRole.valueOf(String.valueOf(name).toUpperCase()));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new BusinessException("Invalid role: " + name);
            }
        }
        return result;
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

    private ProjectSummary toProjectSummary(Project project, ProjectMember member, int memberCount) {
        return new ProjectSummary(
                project.getId(),
                project.getName(),
                project.getKey(),
                memberCount,
                project.getCreatedAt())
                .description(project.getDescription())
                .role(member == null ? null : member.getRole().name())
                .roles(member == null ? null : roleNames(member));
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

    private static java.util.List<com.conductor.generated.model.MemberRoleName> roleNames(ProjectMember member) {
        return member.getRoles().stream()
                .map(r -> com.conductor.generated.model.MemberRoleName.fromValue(r.name())).toList();
    }

    private MemberResponse toMemberResponse(ProjectMember member) {
        User user = member.getUser();
        return new MemberResponse()
                .userId(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .role(member.getRole().name())
                .roles(roleNames(member))
                .joinedAt(member.getJoinedAt());
    }
}
