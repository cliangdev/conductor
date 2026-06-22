package com.conductor.integration.connector.github;

import com.conductor.entity.Connection;
import com.conductor.entity.IntegrationOAuthState;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.integration.AuthType;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.service.ConnectionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GitHub App install lifecycle + repo listing, isolated to the github connector (plain controller,
 * like {@code GcpBillingController}). RESTful: an installation is created via the install redirect and
 * confirmed on the setup callback; repositories are a read-only sub-resource of a connection. Generic —
 * works for any GitHub account and any project; a connection is keyed by (projectId, installationId).
 */
@RestController
@RequestMapping("/api/v1")
public class GitHubAppController {

    private static final String CONNECTOR_ID = "github";

    private final GitHubAppService gitHubAppService;
    private final ConnectionService connectionService;
    private final ConnectionRepository connectionRepository;
    private final IntegrationOAuthStateRepository stateRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ObjectMapper objectMapper;

    @Value("${FRONTEND_URL:http://localhost:3000}")
    private String frontendUrl;

    public GitHubAppController(GitHubAppService gitHubAppService,
                               ConnectionService connectionService,
                               ConnectionRepository connectionRepository,
                               IntegrationOAuthStateRepository stateRepository,
                               ProjectMemberRepository projectMemberRepository,
                               ObjectMapper objectMapper) {
        this.gitHubAppService = gitHubAppService;
        this.connectionService = connectionService;
        this.connectionRepository = connectionRepository;
        this.stateRepository = stateRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.objectMapper = objectMapper;
    }

    /** Begin an installation: returns the GitHub install URL (native account + repo picker). */
    @PostMapping("/projects/{projectId}/integrations/github/installations")
    public ResponseEntity<Map<String, Object>> startInstallation(@PathVariable String projectId) {
        User user = requireAdminOrCreator(projectId);
        if (!gitHubAppService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub App is not configured on this server");
        }
        stateRepository.deleteByExpiresAtBefore(OffsetDateTime.now());

        String state = randomState();
        IntegrationOAuthState row = new IntegrationOAuthState();
        row.setState(state);
        row.setProjectId(projectId);
        row.setConnectorId(CONNECTOR_ID);
        row.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        Map<String, Object> stateConfig = new HashMap<>();
        stateConfig.put("connectedBy", user.getId());
        row.setConfigJson(stateConfig);
        stateRepository.save(row);

        return ResponseEntity.ok(Map.of("installUrl", gitHubAppService.buildInstallUrl(state)));
    }

    /** GitHub's Setup-URL redirect target (public). Validates the installation, upserts the connection. */
    @GetMapping("/integrations/github/app/callback")
    public ResponseEntity<Void> handleSetupCallback(
            @RequestParam(name = "installation_id", required = false) String installationId,
            @RequestParam(name = "setup_action", required = false) String setupAction,
            @RequestParam(name = "state", required = false) String state) {

        Optional<IntegrationOAuthState> stateRow = state != null
                ? stateRepository.findById(state) : Optional.empty();
        if (stateRow.isEmpty() || stateRow.get().getExpiresAt().isBefore(OffsetDateTime.now())
                || installationId == null || installationId.isBlank()) {
            // Invalid/expired state, or no installation yet (e.g. setup_action=request): bounce to the app.
            stateRow.ifPresent(r -> stateRepository.deleteById(r.getState()));
            return redirect(frontendUrl + "/app");
        }

        IntegrationOAuthState s = stateRow.get();
        String projectId = s.getProjectId();
        String connectedBy = s.getConfigJson() != null
                ? String.valueOf(s.getConfigJson().getOrDefault("connectedBy", null)) : null;

        GitHubAppService.InstallationInfo info = gitHubAppService.getInstallation(installationId);

        Map<String, Object> config = new HashMap<>();
        config.put("installationId", installationId);
        config.put("accountLogin", info.accountLogin());
        config.put("installationHtmlUrl", info.htmlUrl());
        config.put("repositorySelection", info.repositorySelection());

        Optional<Connection> existing = connectionRepository
                .findByConnectorIdAndConfigValue(CONNECTOR_ID, "installationId", installationId).stream()
                .filter(c -> c.getProjectId().equals(projectId))
                .findFirst();

        if (existing.isPresent()) {
            Connection conn = existing.get();
            connectionService.updateLabel(conn, info.accountLogin());
            connectionService.updateConfig(conn, config);
        } else {
            Connection conn = connectionService.create(
                    projectId, CONNECTOR_ID, AuthType.APP, info.accountLogin(), connectedBy);
            connectionService.updateConfig(conn, config);
        }

        stateRepository.deleteById(s.getState());
        return redirect(frontendUrl + "/app/projects/" + projectId + "/integrations/github");
    }

    /** Repos the installation can access (read-only sub-resource; granting is done on GitHub). */
    @GetMapping("/projects/{projectId}/integrations/github/connections/{connectionId}/repositories")
    public ResponseEntity<Map<String, Object>> listRepositories(
            @PathVariable String projectId, @PathVariable String connectionId) {
        requireMember(projectId);
        Connection conn = connectionService.getById(connectionId, CONNECTOR_ID)
                .filter(c -> c.getProjectId().equals(projectId))
                .orElseThrow(() -> new EntityNotFoundException("Connection not found: " + connectionId));
        String installationId = String.valueOf(parseConfig(conn.getConfigJson()).get("installationId"));
        List<Map<String, Object>> repositories = gitHubAppService
                .listInstallationRepositories(installationId).stream()
                .map(r -> Map.<String, Object>of("fullName", r.fullName(), "private", r.isPrivate()))
                .toList();
        return ResponseEntity.ok(Map.of("repositories", repositories));
    }

    // ---- helpers ----

    private ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    private String randomState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void requireMember(String projectId) {
        member(projectId);
    }

    private User requireAdminOrCreator(String projectId) {
        ProjectMember member = member(projectId);
        if (member.getRole() != MemberRole.ADMIN && member.getRole() != MemberRole.CREATOR) {
            throw new AccessDeniedException("Requires ADMIN or CREATOR role");
        }
        return currentUser();
    }

    private ProjectMember member(String projectId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, currentUser().getId())
                .orElseThrow(() -> new AccessDeniedException("Not a member of this project"));
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
