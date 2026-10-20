package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.security.JwtAuthenticationDetails;
import com.conductor.security.ProjectScopedPrincipal;
import com.conductor.security.UserApiKeyAuthenticationToken;
import com.conductor.security.WorkflowRunAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (principal instanceof User user) {
            // One membership read answers both "is a member" and "has the role".
            MemberRole role = projectMemberRepository.findByProjectIdAndUserId(projectId, user.getId())
                    .map(member -> member.getRole())
                    .orElseThrow(() -> new ForbiddenException("Not a member of this project"));
            if (role != MemberRole.ADMIN && role != MemberRole.CREATOR) {
                throw new ForbiddenException("Requires ADMIN or CREATOR role");
            }
            return ProjectActor.of(user);
        }
        // Machine credentials pass: they are issued per project and act on its behalf.
        return requireProjectAccess(projectId);
    }

    /**
     * Whether the current request is a person acting in their own browser session: a user JWT sent in
     * the {@code Authorization} header, and nothing else. Excluded on purpose are user API keys (a
     * script, an agent's MCP session), project keys and run tokens (machines), and a JWT that arrived
     * only as the {@code access_token} cookie (the browser attaches it to cross-site requests too).
     */
    public boolean isInteractiveRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof UsernamePasswordAuthenticationToken
                && auth.getPrincipal() instanceof User
                && auth.getDetails() instanceof JwtAuthenticationDetails details
                && details.isFromAuthorizationHeader();
    }

    /**
     * Like {@link #requireProjectAccess}, but only a person in their own session passes — for actions
     * whose whole point is that a human did them, such as a creator's posting consent. A machine
     * credential, a user API key or a cookie-only token gets a 403; a non-member gets the same 404 a
     * missing project gets.
     */
    public User requireInteractiveUser(String projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!isInteractiveRequest()) {
            throw new ForbiddenException(
                    "This needs a person signed in to the Conductor web app; API keys and agents cannot do it");
        }
        User user = (User) auth.getPrincipal();
        if (!isProjectMember(projectId, user.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
        return user;
    }

    /**
     * A member <em>user</em>, however they authenticated (JWT or user API key): for actions that must be
     * bound to a person but may be started by that person's own agent. A project key or run token has no
     * user and gets a 403; a non-member gets the 404 a missing project gets.
     */
    public User requireMemberUser(String projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            throw new ForbiddenException("This needs a signed-in user; project keys and run tokens cannot do it");
        }
        if (!isProjectMember(projectId, user.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
        return user;
    }

    /** The authenticated user, or null for a machine credential or no authentication. */
    public User currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof User user ? user : null;
    }

    /** How the current request authenticated, for the consent audit. */
    public String credentialType() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return "UNKNOWN";
        }
        if (auth instanceof UserApiKeyAuthenticationToken) {
            return "USER_API_KEY";
        }
        if (auth instanceof WorkflowRunAuthenticationToken) {
            return "RUN_TOKEN";
        }
        if (auth instanceof ProjectScopedPrincipal) {
            return "PROJECT_API_KEY";
        }
        if (auth instanceof UsernamePasswordAuthenticationToken && auth.getPrincipal() instanceof User) {
            return auth.getDetails() instanceof JwtAuthenticationDetails details
                    && details.isFromAuthorizationHeader() ? "JWT_HEADER" : "JWT_COOKIE";
        }
        return "UNKNOWN";
    }

    /** Byline for a machine actor — the run id when there is one, so writes stay traceable to a run. */
    private String agentLabel(Authentication auth) {
        if (auth instanceof WorkflowRunAuthenticationToken run && run.getRunId() != null) {
            String runId = run.getRunId();
            return "Agent (run " + runId.substring(0, Math.min(8, runId.length())) + ")";
        }
        return "Agent";
    }

    /**
     * Membership answers are remembered for the rest of the current HTTP request: a publishing request asks
     * "is this user a member / an editor" from the controller's service, then again from every service it
     * calls, and each ask is a round trip to the database. Only a <em>positive</em> answer is remembered, and
     * only on a thread that has a request bound (outside one - a scheduler, a test - nothing is cached), so a
     * membership granted mid-request is seen at once and the cache never outlives the request that read it.
     */
    private static final String MEMBERSHIP_ATTRIBUTE = ProjectSecurityService.class.getName() + ".memberships";

    private static final class Memberships {
        final Set<String> members = ConcurrentHashMap.newKeySet();
        final Map<String, MemberRole> roles = new ConcurrentHashMap<>();
    }

    private static Memberships requestMemberships() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object cached = attributes.getAttribute(MEMBERSHIP_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (cached instanceof Memberships memberships) {
            return memberships;
        }
        Memberships memberships = new Memberships();
        attributes.setAttribute(MEMBERSHIP_ATTRIBUTE, memberships, RequestAttributes.SCOPE_REQUEST);
        return memberships;
    }

    private static String membershipKey(String projectId, String userId) {
        return projectId + "|" + userId;
    }

    public boolean isProjectMember(String projectId, String userId) {
        Memberships cache = requestMemberships();
        String key = membershipKey(projectId, userId);
        if (cache != null && cache.members.contains(key)) {
            return true;
        }
        boolean member = projectMemberRepository.existsByProjectIdAndUserId(projectId, userId);
        if (member && cache != null) {
            cache.members.add(key);
        }
        return member;
    }

    public boolean isProjectAdmin(String projectId, String userId) {
        return memberRole(projectId, userId).map(role -> role == MemberRole.ADMIN).orElse(false);
    }

    public boolean isAdminOrCreator(String projectId, String userId) {
        return memberRole(projectId, userId)
                .map(role -> role == MemberRole.ADMIN || role == MemberRole.CREATOR)
                .orElse(false);
    }

    private Optional<MemberRole> memberRole(String projectId, String userId) {
        Memberships cache = requestMemberships();
        String key = membershipKey(projectId, userId);
        MemberRole known = cache == null ? null : cache.roles.get(key);
        if (known != null) {
            return Optional.of(known);
        }
        Optional<MemberRole> role = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(member -> member.getRole());
        if (role.isPresent() && cache != null) {
            cache.roles.put(key, role.get());
            cache.members.add(key);
        }
        return role;
    }
}
