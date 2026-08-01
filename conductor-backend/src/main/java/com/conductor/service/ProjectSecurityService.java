package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.security.ProjectScopedPrincipal;
import com.conductor.security.WorkflowRunAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class ProjectSecurityService {

    private final ProjectMemberRepository projectMemberRepository;

    public ProjectSecurityService(ProjectMemberRepository projectMemberRepository) {
        this.projectMemberRepository = projectMemberRepository;
    }

    /**
     * Resolves the authenticated caller against a project, or refuses.
     *
     * <p>Two shapes of caller reach a project-scoped endpoint: a human {@link User} principal (JWT or
     * user API key) who must be a member, and a project-scoped machine credential
     * ({@link ProjectScopedPrincipal} — a project API key or a run-scoped MCP token) whose own project
     * must match the one in the path. Note the machine check is against the {@link Authentication},
     * not its principal: those tokens carry the project id as the principal itself.
     *
     * <p>This lives here rather than in each controller because it was copied into five of them, each
     * javadoc'd as mirroring one of the others.
     */
    public ProjectActor requireProjectAccess(String projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;

        if (principal instanceof User user) {
            if (!isProjectMember(projectId, user.getId())) {
                throw new ForbiddenException("Not a member of this project");
            }
            return ProjectActor.of(user);
        }
        if (auth instanceof ProjectScopedPrincipal scoped && projectId.equals(scoped.getProjectId())) {
            return ProjectActor.agent(agentLabel(auth));
        }
        throw new ForbiddenException("Not a member of this project");
    }

    /**
     * Like {@link #requireProjectAccess}, but a human caller must additionally hold ADMIN or CREATOR.
     * Machine credentials pass: they are issued per project and act on its behalf.
     */
    public ProjectActor requireProjectEditor(String projectId) {
        ProjectActor actor = requireProjectAccess(projectId);
        if (!actor.isMachine() && !isAdminOrCreator(projectId, actor.userId())) {
            throw new ForbiddenException("Requires ADMIN or CREATOR role");
        }
        return actor;
    }

    /** Byline for a machine actor — the run id when there is one, so writes stay traceable to a run. */
    private String agentLabel(Authentication auth) {
        if (auth instanceof WorkflowRunAuthenticationToken run && run.getRunId() != null) {
            String runId = run.getRunId();
            return "Agent (run " + runId.substring(0, Math.min(8, runId.length())) + ")";
        }
        return "Agent";
    }

    public boolean isProjectMember(String projectId, String userId) {
        return projectMemberRepository.existsByProjectIdAndUserId(projectId, userId);
    }

    public boolean isProjectAdmin(String projectId, String userId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(member -> member.getRole() == MemberRole.ADMIN)
                .orElse(false);
    }

    public boolean isAdminOrCreator(String projectId, String userId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(member -> member.getRole() == MemberRole.ADMIN || member.getRole() == MemberRole.CREATOR)
                .orElse(false);
    }
}
