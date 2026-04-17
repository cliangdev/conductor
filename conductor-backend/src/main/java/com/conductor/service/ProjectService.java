package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.ProjectVisibility;
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
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.TeamMemberRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final OrgMemberRepository orgMemberRepository;
    private final TeamMemberRepository teamMemberRepository;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            OrgMemberRepository orgMemberRepository,
            TeamMemberRepository teamMemberRepository) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectSecurityService = projectSecurityService;
        this.orgMemberRepository = orgMemberRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request, User creator) {
        Project project = new Project();
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setCreatedBy(creator);
        project.setKey(resolveUniqueKey(request.getName()));
        projectRepository.save(project);

        ProjectMember adminMember = new ProjectMember();
        adminMember.setProject(project);
        adminMember.setUser(creator);
        adminMember.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(adminMember);

        return toProjectResponse(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectSummary> listProjects(User caller) {
        List<Project> explicitProjects = projectRepository.findProjectsByMemberUserId(caller.getId());

        List<Project> accessibleProjects = new ArrayList<>(explicitProjects);

        // Include ORG-visible projects for orgs user belongs to
        List<String> orgIds = orgMemberRepository.findByUserId(caller.getId()).stream()
                .map(om -> om.getOrg().getId())
                .toList();
        for (String orgId : orgIds) {
            List<Project> orgProjects = projectRepository.findByOrgIdAndVisibility(orgId, ProjectVisibility.ORG);
            for (Project p : orgProjects) {
                if (accessibleProjects.stream().noneMatch(ep -> ep.getId().equals(p.getId()))) {
                    accessibleProjects.add(p);
                }
            }
        }

        // Include TEAM-visible projects for teams user belongs to
        List<String> teamIds = teamMemberRepository.findByUserId(caller.getId()).stream()
                .map(tm -> tm.getTeam().getId())
                .toList();
        for (String teamId : teamIds) {
            List<Project> teamProjects = projectRepository.findByTeamIdAndVisibility(teamId, ProjectVisibility.TEAM);
            for (Project p : teamProjects) {
                if (accessibleProjects.stream().noneMatch(ep -> ep.getId().equals(p.getId()))) {
                    accessibleProjects.add(p);
                }
            }
        }

        return accessibleProjects.stream()
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

        if (!canUserAccessProject(caller.getId(), project)) {
            throw new ForbiddenException("You do not have access to this project");
        }

        long memberCount = projectMemberRepository.findByProjectId(projectId).size();

        return toProjectDetail(project, (int) memberCount);
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(String projectId, User caller) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (!canUserAccessProject(caller.getId(), project)) {
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
        if (request.getVisibility() != null) {
            ProjectVisibility newVisibility = ProjectVisibility.valueOf(request.getVisibility().getValue());
            if (newVisibility == ProjectVisibility.TEAM && project.getTeamId() == null
                    && request.getTeamId() == null) {
                throw new BusinessException("teamId is required when visibility is TEAM");
            }
            project.setVisibility(newVisibility);
        }
        if (request.getTeamId() != null) {
            project.setTeamId(request.getTeamId());
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
            long adminCount = projectMemberRepository.countByProjectIdAndRole(projectId, MemberRole.ADMIN);
            if (adminCount <= 1) {
                throw new BusinessException("Cannot remove the last project admin");
            }
        }

        member.setRole(newRole);
        projectMemberRepository.save(member);

        return toMemberResponse(member);
    }

    @Transactional
    public void removeMember(String projectId, String userId, User caller) {
        if (!projectSecurityService.isProjectAdmin(projectId, caller.getId())) {
            throw new AccessDeniedException("Only project admins can remove members");
        }

        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Member not found"));

        if (member.getRole() == MemberRole.ADMIN) {
            long adminCount = projectMemberRepository.countByProjectIdAndRole(projectId, MemberRole.ADMIN);
            if (adminCount <= 1) {
                throw new BusinessException("Cannot remove the last project admin");
            }
        }

        projectMemberRepository.delete(member);
    }

    /**
     * Returns true if the user can read the given project based on its visibility.
     * - null orgId (legacy): only explicit project members
     * - PRIVATE: only explicit project members
     * - ORG: explicit project member OR org member
     * - TEAM: explicit project member OR team member
     * - PUBLIC: any authenticated user
     */
    public boolean canUserAccessProject(String userId, Project project) {
        boolean isExplicitMember = projectMemberRepository.existsByProjectIdAndUserId(project.getId(), userId);

        if (project.getOrgId() == null) {
            return isExplicitMember;
        }

        ProjectVisibility visibility = project.getVisibility() != null
                ? project.getVisibility()
                : ProjectVisibility.PRIVATE;

        return switch (visibility) {
            case PRIVATE -> isExplicitMember;
            case ORG -> isExplicitMember
                    || orgMemberRepository.findByOrgIdAndUserId(project.getOrgId(), userId).isPresent();
            case TEAM -> isExplicitMember
                    || (project.getTeamId() != null
                        && teamMemberRepository.findByTeamIdAndUserId(project.getTeamId(), userId).isPresent());
            case PUBLIC -> true;
        };
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
                .description(project.getDescription())
                .visibility(project.getVisibility() != null
                        ? ProjectResponse.VisibilityEnum.fromValue(project.getVisibility().name())
                        : null)
                .teamId(project.getTeamId());
    }

    private ProjectSummary toProjectSummary(Project project, String role, int memberCount) {
        return new ProjectSummary(
                project.getId(),
                project.getName(),
                project.getKey(),
                memberCount,
                project.getCreatedAt())
                .description(project.getDescription())
                .role(role)
                .visibility(project.getVisibility() != null
                        ? ProjectSummary.VisibilityEnum.fromValue(project.getVisibility().name())
                        : null);
    }

    private ProjectDetail toProjectDetail(Project project, int memberCount) {
        return new ProjectDetail(
                project.getId(),
                project.getName(),
                project.getKey(),
                project.getCreatedBy().getId(),
                memberCount,
                project.getCreatedAt())
                .description(project.getDescription())
                .visibility(project.getVisibility() != null
                        ? ProjectDetail.VisibilityEnum.fromValue(project.getVisibility().name())
                        : null)
                .teamId(project.getTeamId());
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
